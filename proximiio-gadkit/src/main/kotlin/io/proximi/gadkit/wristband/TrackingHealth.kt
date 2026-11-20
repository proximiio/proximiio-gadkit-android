package io.proximi.gadkit.wristband

import io.proximi.sdk.positioning.engine.CustomPositionProviderConnection
import java.time.Duration
import java.time.Instant
import io.proximi.gadkit.GadSettings
import kotlin.time.toJavaDuration

/**
 * Whether the wristband delivers positions during a visit, as iOS `TrackingHealth`.
 * `ProximiMap` reports it as `ProximiMapEvent.TrackingChanged` during a journey and shows no alert:
 * the host app handles the wristband states (PM 2026-10-15).
 *
 * The signal is the SDK's positioning availability: it turns unavailable about 10 s after the last
 * fix from the relay provider, whether the socket failed or the tag stopped sending positions. The
 * position stream is not used for this: its timestamps are publish times, not fix times.
 *
 * In a privacy zone (`Proximiio.privacyZoneStateChanges()`, SDK 6.0.0-beta.31) the state is
 * [PRIVACY_ZONE], however long the band is silent there: the visitor is not lost (Monday "Privacy
 * zones", 2026-10-19).
 */
public enum class TrackingHealth {
    /** Positioning has not been available yet in this visit. */
    WAITING,

    /** Positions arrive. */
    TRACKING,

    /** No positions for [GadSettings.trackingLostAfter]. */
    LOST_PLACE,

    /** No positions for [GadSettings.trackingStoppedAfter], for example an empty battery. */
    STOPPED_RESPONDING,

    /**
     * The visitor is in a privacy zone (`PrivacyZoneState.isPrivate`). The map holds a grey dot at
     * the last position before the zone.
     */
    PRIVACY_ZONE,
    ;

    internal companion object {
        /**
         * Re-evaluation interval of the monitor. It changes [LOST_PLACE] to [STOPPED_RESPONDING]
         * without a new availability event.
         */
        internal val REEVALUATE_INTERVAL: Duration = Duration.ofSeconds(5)

        /**
         * [LOST_PLACE] after [GadSettings.trackingLostAfter] of unavailability, [STOPPED_RESPONDING]
         * after [GadSettings.trackingStoppedAfter]; [PRIVACY_ZONE] while [isPrivate], before these.
         * [isEnded]: the wristband binding has ended ([hasEnded]). It gives [STOPPED_RESPONDING] at
         * once and goes before a privacy zone: an ended binding delivers no more positions, so the
         * SDK never reports the exit from the zone (field log 2026-11-20: a binding superseded inside
         * a zone kept [PRIVACY_ZONE] at every map start; iOS 1.65.2). Pure; covered by tests.
         */
        /**
         * Whether every position provider's connection has ended (superseded, unbound or past its
         * end time). False without a provider. Pure; covered by tests. iOS `hasEnded` (1.65.2).
         */
        internal fun hasEnded(connections: Map<String, CustomPositionProviderConnection>): Boolean =
            connections.isNotEmpty() && connections.values.all { it == CustomPositionProviderConnection.ENDED }

        internal fun evaluate(
            wasEverAvailable: Boolean,
            unavailableSince: Instant?,
            now: Instant,
            settings: GadSettings = GadSettings.STANDARD,
            isPrivate: Boolean = false,
            isEnded: Boolean = false,
        ): TrackingHealth {
            if (isEnded) return STOPPED_RESPONDING
            if (isPrivate) return PRIVACY_ZONE
            if (!wasEverAvailable) return WAITING
            if (unavailableSince == null) return TRACKING
            val unavailable = Duration.between(unavailableSince, now)
            if (unavailable >= settings.trackingStoppedAfter.toJavaDuration()) return STOPPED_RESPONDING
            return if (unavailable >= settings.trackingLostAfter.toJavaDuration()) LOST_PLACE else TRACKING
        }
    }
}
