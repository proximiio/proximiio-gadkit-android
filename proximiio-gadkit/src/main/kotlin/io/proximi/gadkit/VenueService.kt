package io.proximi.gadkit

import kotlinx.coroutines.withContext
import io.proximi.gadkit.map.MapDiagnostics
import android.content.Context
import android.content.pm.ApplicationInfo
import io.proximi.gadkit.map.VenueDataRefresh
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
import io.proximi.sdk.loadCachedRouteNetwork
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
import kotlin.time.DurationUnit

/**
 * The started SDK with the wristband binding's position provider attached, as iOS `VenueService`.
 * The provider is attached once. It follows whichever session the binding holds, so a bind, a
 * take-over or an ended visit needs no re-attach.
 *
 * Positions come from the venue's BlueIoT anchors through the relay-api; the phone scans nothing
 * (`relayOnly`). The API host comes from [GadConfiguration], so a production build reaches only
 * the client's on-prem install.
 *
 * [hosted] uses an SDK the host app has started (`ProximiMapView`). The host app attaches the
 * position provider; the service attaches none.
 */
internal class VenueService private constructor(
    /** The process's only `Proximiio` instance. Do not create a second one. */
    internal val sdk: Proximiio,
    /** The binding client whose provider delivers the wristband's positions. `null` on the host app's SDK. */
    private val binding: BlueiotWristbandBinding?,
    /** True in a debuggable host build, as iOS `#if DEBUG`: journey playback and [DebugLaunch]. */
    internal val isDebugBuild: Boolean,
    settings: GadSettings,
    data: VenueDataRefresh? = null,
) {
    /**
     * Times and distances of the screens, from [GadConfiguration.settings]. A screen reads them
     * when it opens, so a change applies to the next one.
     */
    @Volatile
    internal var settings: GadSettings = settings

    /**
     * The venue's features, geofences and amenities in memory, and the changes of the venue data while
     * the app runs (`Proximiio.venueDataChanges()`). Screens read the data again on a change.
     */
    internal val data: VenueDataRefresh = data ?: VenueDataRefresh(sdk, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

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
    internal suspend fun attachBinding() {
        val provider = binding?.positionProvider ?: return
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
        sampleLog?.cancel()
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
    internal suspend fun playJourney(id: String, speed: Double, loops: Boolean) {
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

    /**
     * Starts the SDK again after it stopped on a refused token (`tokenStatus()` is `REJECTED`). It
     * throws until the API accepts the token again. The binding's provider is attached again.
     */
    internal suspend fun restart(context: Context) {
        authenticateAndStart(sdk)
        attachBinding()
        cacheMapStyle(context.applicationContext.cacheDir)
        // The stop ended the stream of the venue data changes.
        data.stop()
        data.start()
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

    internal companion object {
        /** The SDK configuration for [configuration]. Separate from [start] so tests can check it. */
        internal fun configuration(context: Context, configuration: GadConfiguration): ProximiioConfiguration =
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
                // Visitor reporting: `PUT /core/visitors`, then positions in batches every 60 s
                // (`/core/positions/batch`) and each geofence enter and exit event as it happens
                // (`/core/events`, `eventBatching` stays `false`) to `apiBaseURL`. Events that could not
                // be sent wait on the device and go out in a batch (`/core/events/batch`) once the
                // network is back. The client requires no visitor consent for it (2026-10-14), so it is
                // on in every build. It is the SDK default since 6.0.0-beta.24; it is set here so a
                // later default does not turn it off.
                visitorReportingEnabled = true,
                geofenceExitDelay = configuration.settings.geofenceExitDelay.toDouble(DurationUnit.SECONDS),
                // In a privacy zone `positions()` emits no fix and positioning counts as available, so
                // the map holds a grey dot at the last fix before the zone (SDK 6.0.0-beta.31). The
                // relay's `private` reaches the SDK through `WristbandSession.forwardPrivacy`.
                withholdsPositionsInPrivacyZones = true,
            )

        /**
         * Authenticates, starts positioning and installs the route network. Before the API has
         * accepted the token in this run, the network comes from the stored features first;
         * otherwise it is downloaded, and the stored features are the fallback.
         */
        private suspend fun authenticateAndStart(sdk: Proximiio) {
            sdk.authenticate()
            sdk.start()
            val token = sdk.tokenStatus()
            if (token != Proximiio.TokenStatus.VERIFIED && sdk.loadCachedRouteNetwork() > 0) {
                FieldLog.log("venue: started on the stored data · token ${token.rawValue}")
                return
            }
            try {
                sdk.loadRouteNetwork()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (sdk.loadCachedRouteNetwork() == 0) throw error
                FieldLog.log("venue: route network from the stored features: ${error.message}")
            }
        }

        /** The venue data of the hosted screens. The next hosted screen on the same SDK reuses it. Main thread only. */
        private var hostedData: VenueDataRefresh? = null

        /**
         * A venue on the host app's started SDK. Requests no permission and attaches no position
         * provider.
         *
         * The first call on an SDK installs the route network from the SDK's feature cache, or
         * downloads it when the cache has no path, and starts reading the venue data. The later calls
         * on the same SDK reuse the route network and the data in memory, which follows
         * `venueDataChanges()` also while no screen is open. After the SDK stopped, which ends the
         * stream of the changes, or when the SDK had no features, the next call starts again (iOS 1.23.0).
         */
        internal suspend fun hosted(context: Context, sdk: Proximiio, settings: GadSettings): VenueService = withContext(Dispatchers.Main.immediate) {
            val isDebugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            hostedData?.let { data ->
                if (data.sdk === sdk && data.isFollowing && data.features().isNotEmpty()) {
                    return@withContext VenueService(sdk, null, isDebugBuild, settings, data)
                }
            }
            val started = System.nanoTime()
            var paths = withContext(Dispatchers.Default) { sdk.loadCachedRouteNetwork() }
            if (paths == 0) {
                try {
                    paths = sdk.loadRouteNetwork()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FieldLog.log("hosted map: route network not loaded: ${error.message}")
                }
            }
            FieldLog.log("hosted map: route network $paths paths in ${MapDiagnostics.Timing.text(MapDiagnostics.seconds(started))}")
            hostedData?.stop()
            val data = VenueDataRefresh(sdk, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
            hostedData = data
            data.start()
            VenueService(sdk, null, isDebugBuild, settings, data)
        }

        /**
         * Reads the permissions, authenticates, starts positioning, loads the route network and
         * attaches the binding's position provider. The order matters; see
         * proximiio-blueiot-minimal-android `Venue.start`. Then fetches the map style in the
         * background, so call it at launch, before the first map screen.
         *
         * Without a connection the SDK starts on the data stored on the phone when the API accepted
         * the token on this phone before (`tokenStatus()` is `UNVERIFIED`, SDK 6.0.0-beta.33).
         */
        internal suspend fun start(context: Context, configuration: GadConfiguration, binding: BlueiotWristbandBinding): VenueService {
            val sdk = Proximiio(context.applicationContext, configuration(context, configuration))
            sdk.refreshPermissions()
            authenticateAndStart(sdk)
            val isDebugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val venue = VenueService(sdk, binding, isDebugBuild, configuration.settings)
            venue.attachBinding()
            // The map session's default cache directory, so the session finds the style.
            venue.cacheMapStyle(context.applicationContext.cacheDir)
            venue.data.start()
            if (FieldLog.isEnabled) venue.logReporting(configuration)
            return venue
        }
    }
}
