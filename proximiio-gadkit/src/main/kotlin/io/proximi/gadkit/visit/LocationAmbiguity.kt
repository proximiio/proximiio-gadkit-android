package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.VenueLevel
import java.time.Duration
import java.time.Instant

/**
 * Decides when the visitor must confirm where they are (Figma "Other Use Cases", Edge 04 —
 * Ambiguous location), as iOS `LocationAmbiguity`:
 *
 * - EC04a (two or more journey stops nearby) is not used: artworks are far enough apart that
 *   the question is not needed (PM review 2026-10-12)
 * - EC04b ("Which one are you in?" between galleries) and EC04c (the visitor picks the stop
 *   manually) are not used: the PM does not want these views (Monday 2026-10-15)
 * - EC04d: the position is uncertain near the active stop: a soft prompt
 *
 * Pure; covered by tests.
 */
internal sealed interface LocationAmbiguity {
    /** EC04d: the visitor is probably at this stop, but the position is uncertain. */
    data class LowConfidence(val stop: JourneyStop) : LocationAmbiguity

    /** The kind, for suppressing a prompt the visitor has just answered. */
    val kind: String
        get() = when (this) {
            is LowConfidence -> "low"
        }

    data class Input(
        val coordinate: Coordinate,
        val level: Double?,
        val horizontalAccuracy: Double,
        val activeStop: JourneyStop?,
    )

    companion object {
        /** Positions less accurate than this are uncertain (metres). */
        internal const val UNCERTAIN_ACCURACY: Double = 8.0

        /** A prompt kind is not shown again for this long after the visitor answers it. */
        internal val QUIET_PERIOD: Duration = Duration.ofSeconds(120)

        /** Positions less accurate than [uncertainAccuracyMeters] near the active stop are uncertain. */
        internal fun evaluate(input: Input, uncertainAccuracyMeters: Double = UNCERTAIN_ACCURACY): LocationAmbiguity? {
            val active = input.activeStop ?: return null
            val visitor = input.level
            val sameLevel = active.level == null || visitor == null || VenueLevel.same(active.level, visitor)
            if (input.horizontalAccuracy > uncertainAccuracyMeters && sameLevel &&
                input.coordinate.distanceTo(active.coordinate) <= input.horizontalAccuracy
            ) {
                return LowConfidence(active)
            }
            return null
        }

        /** True when the visitor answered a prompt of [kind] less than [quietPeriod] (120 s) ago. */
        internal fun isQuiet(kind: String, answered: Map<String, Instant>, now: Instant, quietPeriod: Duration = QUIET_PERIOD): Boolean {
            val time = answered[kind] ?: return false
            return Duration.between(time, now) < quietPeriod
        }
    }
}
