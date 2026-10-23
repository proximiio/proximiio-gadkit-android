package io.proximi.gadkit.wristband

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
         * after [GadSettings.trackingStoppedAfter]; [PRIVACY_ZONE] while [isPrivate], before every
         * other state. Pure; covered by tests.
         */
        internal fun evaluate(
            wasEverAvailable: Boolean,
            unavailableSince: Instant?,
            now: Instant,
            settings: GadSettings = GadSettings.STANDARD,
            isPrivate: Boolean = false,
        ): TrackingHealth {
            if (isPrivate) return PRIVACY_ZONE
            if (!wasEverAvailable) return WAITING
            if (unavailableSince == null) return TRACKING
            val unavailable = Duration.between(unavailableSince, now)
            if (unavailable >= settings.trackingStoppedAfter.toJavaDuration()) return STOPPED_RESPONDING
            return if (unavailable >= settings.trackingLostAfter.toJavaDuration()) LOST_PLACE else TRACKING
        }
    }
}
