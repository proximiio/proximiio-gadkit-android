package io.proximi.gadkit.wristband

import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.Coordinate
import io.proximi.sdk.Proximiio
import io.proximi.sdk.positionProviderConnections
import io.proximi.sdk.positioningAvailabilityChanges
import io.proximi.sdk.positions
import io.proximi.sdk.privacyZoneStateChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import io.proximi.gadkit.GadSettings

/**
 * Reports when the wristband stops delivering positions during a visit (Figma "Wristband", edge
 * cases "Lost tracking mid-narration" and "Device removed or battery dead"), as iOS `TrackingMonitor`.
 * `VisitScreen` reports the state as `VisitEvent.TrackingChanged` and shows no alert (PM 2026-10-15).
 *
 * The signal is the SDK's `positioningAvailabilityChanges()`: it turns `false` about 10 s after the
 * last fix from the relay provider, whether the socket failed or the tag stopped sending positions. Core's
 * [TrackingHealth] turns it into [TrackingHealth.LOST_PLACE] and, after
 * [GadSettings.trackingStoppedAfter], [TrackingHealth.STOPPED_RESPONDING]; the monitor re-evaluates
 * every [TrackingHealth.REEVALUATE_INTERVAL] so the second follows while nothing else changes. The
 * position stream only feeds [lastFix], for "Probably at …". In a privacy zone
 * (`privacyZoneStateChanges()`: the phone's own zone or the relay's `private`) the state is
 * [TrackingHealth.PRIVACY_ZONE].
 *
 * [start] it when the visit screen appears and [stop] it when it goes, as iOS `onAppear` /
 * `onDisappear`; [scope] is the screen's (a ViewModel's) scope.
 */
internal class TrackingMonitor(
    private val sdk: Proximiio,
    private val scope: CoroutineScope,
    private val settings: GadSettings = GadSettings.STANDARD,
    private val now: () -> Instant = Instant::now,
) {
    /** The last position, for "Probably at …". */
    data class LastFix(val coordinate: Coordinate, val level: Double?, val accuracy: Double)

    private val mutableHealth = MutableStateFlow(TrackingHealth.WAITING)
    val health: StateFlow<TrackingHealth> = mutableHealth.asStateFlow()

    private val mutableLastFix = MutableStateFlow<LastFix?>(null)
    val lastFix: StateFlow<LastFix?> = mutableLastFix.asStateFlow()

    private var wasEverAvailable = false
    private var unavailableSince: Instant? = null
    private var isPrivate = false
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            var logged = 0L
            sdk.positions().collect { update ->
                mutableLastFix.value = LastFix(
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
        // Privacy zone: the phone's own zone or the relay's `private`.
        jobs += scope.launch {
            sdk.privacyZoneStateChanges().collect { state ->
                FieldLog.log(
                    "privacy zone: private ${state.isPrivate} · zone ${state.isInPrivacyZone} · " +
                        "relay ${state.positioningSourcePrivateSince?.let(BindingLog::iso) ?: "-"}",
                )
                isPrivate = state.isPrivate
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
        val health = TrackingHealth.evaluate(wasEverAvailable, unavailableSince, now(), settings, isPrivate)
        if (health == mutableHealth.value) return
        FieldLog.log("tracking health: $health")
        mutableHealth.value = health
    }
}
