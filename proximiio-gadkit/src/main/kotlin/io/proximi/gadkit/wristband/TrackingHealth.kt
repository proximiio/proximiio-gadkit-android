package io.proximi.gadkit.wristband

import java.time.Duration
import java.time.Instant
import io.proximi.gadkit.GadSettings
import kotlin.time.toJavaDuration

/**
 * Whether the wristband still delivers positions during a visit (Figma "Wristband", edge cases
 * "Lost tracking mid-narration" and "Device removed or battery dead").
 *
 * The signal is the SDK's positioning availability: it turns unavailable about 10 s after the
 * last fix from the relay provider, whether the socket failed or the tag stopped sending positions. Unavailable
 * for [GadSettings.trackingLostAfter] is "lost your place"; unavailable for
 * [GadSettings.trackingStoppedAfter] is "stopped responding".
 * The position stream is not used for this: its timestamps are publish times, not fix times.
 */
internal enum class TrackingHealth {
    /** Positioning has not been available yet in this visit. Nothing is shown. */
    WAITING,
    TRACKING,

    /** "We've lost your place in the room". */
    LOST_PLACE,

    /** "Your wristband has stopped responding". */
    STOPPED_RESPONDING,
    ;

    internal companion object {
        /**
         * Re-evaluation interval of the monitor. It changes "lost your place" to "stopped
         * responding" without a new availability event.
         */
        internal val REEVALUATE_INTERVAL: Duration = Duration.ofSeconds(5)

        /**
         * "Lost your place" after [GadSettings.trackingLostAfter] of unavailability, "stopped
         * responding" after [GadSettings.trackingStoppedAfter]. Pure; covered by tests.
         */
        internal fun evaluate(
            wasEverAvailable: Boolean,
            unavailableSince: Instant?,
            now: Instant,
            settings: GadSettings = GadSettings.STANDARD,
        ): TrackingHealth {
            if (!wasEverAvailable) return WAITING
            if (unavailableSince == null) return TRACKING
            val unavailable = Duration.between(unavailableSince, now)
            if (unavailable >= settings.trackingStoppedAfter.toJavaDuration()) return STOPPED_RESPONDING
            return if (unavailable >= settings.trackingLostAfter.toJavaDuration()) LOST_PLACE else TRACKING
        }
    }
}
