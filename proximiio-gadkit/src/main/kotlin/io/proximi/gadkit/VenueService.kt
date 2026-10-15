package io.proximi.gadkit

import android.content.Context
import android.content.pm.ApplicationInfo
import io.proximi.map.live.VenueStyleLoader
import io.proximi.sdk.Proximiio
import io.proximi.sdk.ProximiioConfiguration
import io.proximi.sdk.attachPositionProvider
import io.proximi.sdk.blueiot.binding.BlueiotWristbandBinding
import io.proximi.sdk.core.sync.SyncEvent
import io.proximi.sdk.detachPositionProvider
import io.proximi.sdk.fetchJourney
import io.proximi.sdk.floors
import io.proximi.sdk.journey.JourneyPlaybackConfiguration
import io.proximi.sdk.journey.JourneyPlaybackProvider
import io.proximi.sdk.loadRouteNetwork
import io.proximi.sdk.positioning.engine.CustomPositionProviding
import io.proximi.sdk.refreshPermissions
import io.proximi.sdk.service.ProximiioServiceOptions
import io.proximi.sdk.syncEvents
import java.io.File
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The started SDK with the wristband binding's position provider attached, as iOS `VenueService`.
 * The provider is attached once. It follows whichever session the binding holds, so a bind, a
 * take-over or an ended visit needs no re-attach.
 *
 * Positions come from the venue's BlueIoT anchors through the relay-api; the phone scans nothing
 * (`relayOnly`). The API host comes from [GadConfiguration], so a production build reaches only
 * the client's on-prem install.
 */
public class VenueService private constructor(
    /** The app's only `Proximiio` instance. Other features of the host app use it too; do not create a second one. */
    public val sdk: Proximiio,
    private val binding: BlueiotWristbandBinding,
    /** True in a debuggable host build, as iOS `#if DEBUG`: journey playback and [DebugLaunch]. */
    internal val isDebugBuild: Boolean,
) {
    /** Name of the attached position provider. Providers are detached by name. */
    private var attachedProvider: String? = null

    /** Work for the life of the process: the field log's collectors. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Writes the attached binding provider's samples to [FieldLog]. */
    private var sampleLog: Job? = null

    /**
     * Attaches the binding's position provider in place of the attached provider. A re-attach
     * stops and starts the provider, which reopens its relay-api stream. In debug builds it also
     * ends journey playback.
     */
    public suspend fun attachBinding() {
        val provider = binding.positionProvider
        attach(provider)
        if (FieldLog.isEnabled) logSamples(provider)
    }

    /** Attaches [provider] in place of the attached one. Debug journey playback uses it too. */
    internal suspend fun attach(provider: CustomPositionProviding) {
        detachProvider()
        attachedProvider = provider.name
        sdk.attachPositionProvider(provider)
    }

    /**
     * Logs every sample of [provider]: the relay-api positions before the SDK engine processes
     * them. The binding has already dropped repeated positions. `age` is the relay-api
     * `server_time − ts` plus the time until the line is written. Network transit is not in it.
     */
    private fun logSamples(provider: CustomPositionProviding) {
        sampleLog = scope.launch {
            val levels = buildMap { sdk.floors().forEach { putIfAbsent(it.id, it.level.toInt()) } }
            provider.samples().collect { sample ->
                val floor = sample.floorId?.let { levels[it]?.toString() ?: it } ?: "-"
                FieldLog.log(
                    String.format(
                        Locale.ROOT,
                        "relay %.6f,%.6f L%s acc %.1f age %.2f",
                        sample.coordinate.latitude,
                        sample.coordinate.longitude,
                        floor,
                        sample.horizontalAccuracy,
                        (System.currentTimeMillis() - sample.timestamp) / 1000.0,
                    ),
                )
            }
        }
    }

    /**
     * Debug builds only, as iOS `VenueService.playJourney`. Replaces the attached provider with
     * playback of a journey stored on Proximi.io; the positions are generated on the phone.
     * [attachBinding] ends the playback.
     */
    public suspend fun playJourney(id: String, speed: Double, loops: Boolean) {
        check(isDebugBuild) { "Journey playback is for debug builds only" }
        val journey = sdk.fetchJourney(id)
        journey.validationFailure()?.let { throw IllegalStateException("Journey cannot be played: $it") }
        attach(
            JourneyPlaybackProvider(
                journey = journey,
                configuration = JourneyPlaybackConfiguration(speed = speed, loops = loops, runsInBackground = true),
            ),
        )
    }

    private suspend fun detachProvider() {
        sampleLog?.cancel()
        sampleLog = null
        val name = attachedProvider ?: return
        sdk.detachPositionProvider(name)
        attachedProvider = null
    }

    /**
     * Fetches the organisation's map style in the background. The map library writes it to
     * [caches], and a map screen opened later starts on it instead of an empty style. Without it,
     * the first map screen after an install fetches the style while it is on screen.
     */
    private fun cacheMapStyle(caches: File) {
        scope.launch {
            try {
                VenueStyleLoader.load(sdk, caches)
                FieldLog.log("map style cached")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FieldLog.log("map style not cached: ${e.message}")
            }
        }
    }

    /** Field log: the visitor id and the SDK's sync events. */
    private suspend fun logReporting(configuration: GadConfiguration) {
        // The visitor id finds this phone's visitor, positions and events on the backend.
        val consent = sdk.visitorReportingConsent()
        FieldLog.log("visitor reporting ${if (consent) "on" else "off"} · visitor ${sdk.visitorId()} · ${configuration.apiBaseUrl}")
        // Registration and upload failures of the reporting client reach the field log through
        // `Proximiio.logSink` (`sdk Reporting error: …`). `SyncFailed` also arrives after repeated failures.
        scope.launch {
            sdk.syncEvents().collect { event ->
                if (event is SyncEvent.SyncFailed || event is SyncEvent.SyncCompleted) FieldLog.log("sync: $event")
            }
        }
    }

    public companion object {
        /** The SDK configuration for [configuration]. Separate from [start] so tests can check it. */
        public fun configuration(context: Context, configuration: GadConfiguration): ProximiioConfiguration =
            configuration(
                configuration,
                ProximiioServiceOptions(
                    notificationChannelName = context.getString(R.string.positioning_channel_name),
                    notificationChannelDescription = context.getString(R.string.positioning_channel_description),
                    notificationTitle = context.getString(R.string.positioning_notification_title),
                    notificationText = context.getString(R.string.positioning_notification_text),
                    // The SDK's default (0) is no icon, and Android refuses the foreground service.
                    notificationIcon = R.drawable.ic_notification_positioning,
                    includesConnectedDeviceType = false,
                    holdsWakeLock = true,
                ),
            )

        /** [configuration] with the foreground service's [serviceOptions]. Pure; covered by tests. */
        internal fun configuration(configuration: GadConfiguration, serviceOptions: ProximiioServiceOptions): ProximiioConfiguration =
            ProximiioConfiguration.relayOnly(token = configuration.applicationToken, serviceOptions = serviceOptions).copy(
                apiBaseURL = URI(configuration.apiBaseUrl),
                // No fallback hosts: a production build must not fall back to *.proximi.fi.
                fallbackAPIBaseURLs = emptyList(),
                // Visitor reporting: `PUT /core/visitors`, then positions (`/core/positions/batch`) and
                // geofence enter and exit events (`/core/events/batch`) in batches every 60 s, to
                // `apiBaseURL`. The client requires no visitor consent for it (2026-10-14), so it is on
                // in every build. The SDK default is off.
                visitorReportingEnabled = true,
            )

        /**
         * Reads the permissions, authenticates, starts positioning, loads the route network and
         * attaches the binding's position provider. The order matters; see
         * proximiio-blueiot-minimal-android `Venue.start`. Then fetches the map style in the
         * background, so call it at launch, before the first map screen.
         */
        public suspend fun start(context: Context, configuration: GadConfiguration, binding: BlueiotWristbandBinding): VenueService {
            val sdk = Proximiio(context.applicationContext, configuration(context, configuration))
            sdk.refreshPermissions()
            sdk.authenticate()
            sdk.start()
            sdk.loadRouteNetwork()
            val isDebugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val venue = VenueService(sdk, binding, isDebugBuild)
            venue.attachBinding()
            // The map session's default cache directory, so the session finds the style.
            venue.cacheMapStyle(context.applicationContext.cacheDir)
            if (FieldLog.isEnabled) venue.logReporting(configuration)
            return venue
        }
    }
}
