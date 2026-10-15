package io.proximi.gadkit

import android.content.pm.ApplicationInfo
import android.content.Context
import io.proximi.gadkit.R
import io.proximi.sdk.Proximiio
import io.proximi.sdk.ProximiioConfiguration
import io.proximi.sdk.attachPositionProvider
import io.proximi.sdk.blueiot.binding.BlueiotWristbandBinding
import io.proximi.sdk.detachPositionProvider
import io.proximi.sdk.fetchJourney
import io.proximi.sdk.journey.JourneyPlaybackConfiguration
import io.proximi.sdk.journey.JourneyPlaybackProvider
import io.proximi.sdk.loadRouteNetwork
import io.proximi.sdk.positioning.engine.CustomPositionProviding
import io.proximi.sdk.refreshPermissions
import io.proximi.sdk.service.ProximiioServiceOptions
import java.net.URI

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

    /** Attaches the binding's position provider in place of the attached provider. In debug builds it also ends journey playback. */
    public suspend fun attachBinding() {
        attach(binding.positionProvider)
    }

    /** Attaches [provider] in place of the attached one. Debug journey playback uses it too. */
    internal suspend fun attach(provider: CustomPositionProviding) {
        detachProvider()
        attachedProvider = provider.name
        sdk.attachPositionProvider(provider)
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
        val name = attachedProvider ?: return
        sdk.detachPositionProvider(name)
        attachedProvider = null
    }

    public companion object {
        /** The SDK configuration for [configuration]. Separate from [start] so tests can check it. */
        public fun configuration(context: Context, configuration: GadConfiguration): ProximiioConfiguration =
            ProximiioConfiguration.relayOnly(
                token = configuration.applicationToken,
                serviceOptions = ProximiioServiceOptions(
                    notificationChannelName = context.getString(R.string.positioning_channel_name),
                    notificationChannelDescription = context.getString(R.string.positioning_channel_description),
                    notificationTitle = context.getString(R.string.positioning_notification_title),
                    notificationText = context.getString(R.string.positioning_notification_text),
                    // The SDK's default (0) is no icon, and Android refuses the foreground service.
                    notificationIcon = R.drawable.ic_notification_positioning,
                    includesConnectedDeviceType = false,
                    holdsWakeLock = true,
                ),
            ).copy(
                apiBaseURL = URI(configuration.apiBaseUrl),
                // No fallback hosts: a production build must not fall back to *.proximi.fi.
                fallbackAPIBaseURLs = emptyList(),
            )

        /**
         * Reads the permissions, authenticates, starts positioning, loads the route network and
         * attaches the binding's position provider. The order matters; see
         * proximiio-blueiot-minimal-android `Venue.start`.
         */
        public suspend fun start(context: Context, configuration: GadConfiguration, binding: BlueiotWristbandBinding): VenueService {
            val sdk = Proximiio(context.applicationContext, configuration(context, configuration))
            sdk.refreshPermissions()
            sdk.authenticate()
            sdk.start()
            sdk.loadRouteNetwork()
            val isDebugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return VenueService(sdk, binding, isDebugBuild).also { it.attachBinding() }
        }
    }
}
