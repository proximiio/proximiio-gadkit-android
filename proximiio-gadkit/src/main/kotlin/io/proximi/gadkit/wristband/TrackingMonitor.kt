package io.proximi.gadkit.wristband

import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.wristband.TrackingHealth
import io.proximi.sdk.Proximiio
import io.proximi.sdk.positionProviderConnections
import io.proximi.sdk.positioningAvailabilityChanges
import io.proximi.sdk.positions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Reports when the wristband stops delivering positions during a visit (Figma "Wristband", edge
 * cases "Lost tracking mid-narration" and "Device removed or battery dead"), as iOS `TrackingMonitor`.
 *
 * The signal is the SDK's `positioningAvailabilityChanges()`: it turns `false` about 10 s after the
 * last fix from the relay provider, whether the socket failed or the tag stopped sending positions. Core's
 * [TrackingHealth] turns it into "lost your place" and, after [TrackingHealth.STOPPED_AFTER],
 * "stopped responding"; the monitor re-evaluates every [TrackingHealth.REEVALUATE_INTERVAL] so the
 * second follows while nothing else changes. The position stream only feeds "Last Seen".
 *
 * [start] it when the visit screen appears and [stop] it when it goes, as iOS `onAppear` /
 * `onDisappear`; [scope] is the screen's (a ViewModel's) scope.
 */
internal class TrackingMonitor(private val sdk: Proximiio, private val scope: CoroutineScope, private val now: () -> Instant = Instant::now) {
    /** The last position, for "Last Seen". [time] is the fix time. */
    data class LastFix(val time: Instant, val coordinate: Coordinate, val level: Double?, val accuracy: Double)

    private val mutableHealth = MutableStateFlow(TrackingHealth.WAITING)
    val health: StateFlow<TrackingHealth> = mutableHealth.asStateFlow()

    private val mutableLastFix = MutableStateFlow<LastFix?>(null)
    val lastFix: StateFlow<LastFix?> = mutableLastFix.asStateFlow()

    private var wasEverAvailable = false
    private var unavailableSince: Instant? = null
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            var logged = 0L
            sdk.positions().collect { update ->
                mutableLastFix.value = LastFix(
                    time = Instant.ofEpochMilli(update.timestamp),
                    coordinate = Coordinate(update.coordinate.latitude, update.coordinate.longitude),
                    level = update.level,
                    accuracy = update.horizontalAccuracy,
                )
                // Once per second, as on iOS.
                if (FieldLog.isEnabled && System.currentTimeMillis() - logged >= 1_000) {
                    logged = System.currentTimeMillis()
                    FieldLog.log(
                        String.format(
                            Locale.ROOT, "position %s %.6f,%.6f L%s acc %.1f", update.source,
                            update.coordinate.latitude, update.coordinate.longitude,
                            update.level?.toInt()?.toString() ?: "-", update.horizontalAccuracy,
                        ),
                    )
                }
            }
        }
        jobs += scope.launch {
            sdk.positioningAvailabilityChanges().collect { isAvailable ->
                FieldLog.log("positioning available: $isAvailable")
                if (isAvailable) {
                    wasEverAvailable = true
                    unavailableSince = null
                } else if (unavailableSince == null) {
                    unavailableSince = now()
                }
                reevaluate()
            }
        }
        jobs += scope.launch {
            while (true) {
                delay(TrackingHealth.REEVALUATE_INTERVAL.toMillis())
                reevaluate()
                if (FieldLog.isEnabled) {
                    val connections = sdk.positionProviderConnections().mapValues { it.value.name }
                    FieldLog.log("connections $connections health ${health.value}")
                }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun reevaluate() {
        val health = TrackingHealth.evaluate(wasEverAvailable, unavailableSince, now())
        if (health == mutableHealth.value) return
        FieldLog.log("tracking health: $health")
        mutableHealth.value = health
    }

    /** "Gallery 2, 9:38": the nearest place on the last floor and the time of the last position. */
    fun lastSeen(places: List<VenuePlace>, locale: Locale = Locale.getDefault()): String? {
        val fix = lastFix.value ?: return null
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(fix.time.atZone(ZoneId.systemDefault()))
        val place = VenuePlace.nearest(fix.coordinate, fix.level, places) ?: return time
        return "${place.title}, $time"
    }
}
