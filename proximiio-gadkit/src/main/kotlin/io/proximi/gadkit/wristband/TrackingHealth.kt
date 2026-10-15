package io.proximi.gadkit.wristband

import java.time.Duration
import java.time.Instant

/**
 * Whether the wristband still delivers positions during a visit (Figma "Wristband", edge cases
 * "Lost tracking mid-narration" and "Device removed or battery dead").
 *
 * The signal is the SDK's positioning availability: it turns unavailable about 10 s after the
 * last fix from the relay provider, whether the socket failed or the tag stopped sending positions. Unavailable
 * for [LOST_AFTER] is "lost your place"; unavailable for [STOPPED_AFTER] is "stopped responding".
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
         * Unavailability before "lost your place". The SDK reports unavailable about 10 s after
         * the last fix, so this is about 2 min after it (PM review 2026-10-12).
         */
        internal val LOST_AFTER: Duration = Duration.ofSeconds(110)

        /**
         * Unavailability before "stopped responding". The SDK reports unavailable about 10 s
         * after the last fix, so this is about 3 min after it.
         */
        internal val STOPPED_AFTER: Duration = Duration.ofSeconds(170)

        /**
         * Re-evaluation interval of the monitor. It changes "lost your place" to "stopped
         * responding" without a new availability event.
         */
        internal val REEVALUATE_INTERVAL: Duration = Duration.ofSeconds(5)

        /** Pure; covered by tests. */
        internal fun evaluate(wasEverAvailable: Boolean, unavailableSince: Instant?, now: Instant): TrackingHealth {
            if (!wasEverAvailable) return WAITING
            if (unavailableSince == null) return TRACKING
            val unavailable = Duration.between(unavailableSince, now)
            if (unavailable >= STOPPED_AFTER) return STOPPED_RESPONDING
            return if (unavailable >= LOST_AFTER) LOST_PLACE else TRACKING
        }
    }
}
