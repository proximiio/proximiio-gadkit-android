package io.proximi.gadkit.map

import kotlin.math.abs

/**
 * Level comparison, as iOS `VenueLevel`. A level is a `Double`, and a mezzanine can be 1.5.
 * Rounding to an integer would merge level 1.5 with level 2, so levels are compared with a
 * tolerance. Labels ("Level 2"), the level colour and the level change still round.
 */
internal object VenueLevel {
    /** Two levels closer than this are the same level. */
    const val TOLERANCE = 0.01

    /** `true` when [a] and [b] are the same level. Pure; covered by tests. */
    fun same(a: Double, b: Double): Boolean = abs(a - b) < TOLERANCE

    /** `true` when either level is unknown or both are the same level. Pure; covered by tests. */
    fun matches(a: Double?, b: Double?): Boolean = a == null || b == null || same(a, b)
}
