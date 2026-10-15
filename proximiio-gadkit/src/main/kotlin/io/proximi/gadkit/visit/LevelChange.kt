package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.VenueFeature
import io.proximi.gadkit.roundedInt

/** The visitor's floor changed from [from] to [to], as iOS `LevelChange`. */
internal data class LevelChange(val from: Int, val to: Int) {
    val isUp: Boolean get() = to > from
}

/**
 * Detects a change of the visitor's floor, as iOS `LevelChangeDetector`. A floor counts as changed
 * after [confirmations] consecutive fixes on the new floor. The default is 1: the map switches
 * floors on the first fix on the new floor, and the animation starts on the same fix. Covered by tests.
 */
internal class LevelChangeDetector(private val confirmations: Int = 1) {
    /** The visitor's floor, `null` before the first fix with a floor. */
    internal var level: Int? = null
        private set
    private var candidate: Int? = null
    private var candidateFixes = 0

    /** Feeds the floor of one fix. Returns the change when the new floor is confirmed. The first floor seen is no change. */
    internal fun update(fixLevel: Double?): LevelChange? {
        if (fixLevel == null) return null
        val next = fixLevel.roundedInt()
        val current = level
        if (current == null) {
            level = next
            return null
        }
        if (next == current) {
            candidate = null
            candidateFixes = 0
            return null
        }
        if (candidate == next) {
            candidateFixes += 1
        } else {
            candidate = next
            candidateFixes = 1
        }
        if (candidateFixes < confirmations) return null
        level = next
        candidate = null
        candidateFixes = 0
        return LevelChange(current, next)
    }
}

/**
 * A lift cabin outline: a venue polygon feature of type `elevator`, as iOS `LiftArea`. The outline
 * is drawn on one floor, and a lift shaft is vertical, so it applies on every floor.
 */
internal class LiftArea(private val outline: Gallery) {
    internal fun contains(coordinate: Coordinate): Boolean = outline.contains(coordinate, level = null)

    /**
     * The floor a ride in this lift goes to: the target of the route's next level change when it
     * leaves [from] by this lift ([featureType] `elevator` at [coordinate] inside the outline). A
     * level change by stairs or by another lift is not this ride (PM 2026-10-15: a visitor in a
     * lift saw "Level 3", the target of the route's stairs). Pure; covered by tests.
     */
    internal fun rideTarget(featureType: String, fromLevel: Double, toLevel: Double, coordinate: Coordinate, from: Int): Int? {
        if (featureType != "elevator" || fromLevel.roundedInt() != from || !contains(coordinate)) return null
        return toLevel.roundedInt()
    }

    internal companion object {
        internal fun all(features: List<VenueFeature>): List<LiftArea> = features.mapNotNull { feature ->
            if (feature.type != "elevator") return@mapNotNull null
            val geometry = feature.geometry ?: return@mapNotNull null
            val ring = Gallery.outerRing(geometry.type, geometry.coordinates) ?: return@mapNotNull null
            LiftArea(Gallery(feature.id, "", 0.0, ring))
        }
    }
}

/**
 * Detects a lift ride, as iOS `LiftDetector`: the visitor counts as in the lift after [dwellMillis]
 * of fixes inside a lift area, so walking past a lift is no ride. The ride ends after [exitFixes]
 * consecutive fixes outside. Covered by tests.
 */
internal class LiftDetector(private val dwellMillis: Long = 3_000, private val exitFixes: Int = 2) {
    internal enum class Event { BOARDED, LEFT }

    internal var isRiding: Boolean = false
        private set
    private var insideSince: Long? = null
    private var outsideFixes = 0

    /** Feeds one fix: whether it is inside a lift area, and its time in milliseconds. */
    internal fun update(inLift: Boolean, atMillis: Long): Event? {
        if (inLift) {
            outsideFixes = 0
            val since = insideSince ?: atMillis
            insideSince = since
            if (isRiding || atMillis - since < dwellMillis) return null
            isRiding = true
            return Event.BOARDED
        }
        outsideFixes += 1
        if (outsideFixes < exitFixes) return null
        insideSince = null
        if (!isRiding) return null
        isRiding = false
        return Event.LEFT
    }
}

/** What the level change overlay shows: the ride, then the arrival on the new floor (iOS `LevelChangeCard`). */
internal data class LevelChangeCard(
    val phase: Phase,
    val from: Int,
    /** The floor the visitor goes to. During a ride it comes from the route's level change at this lift, and is `null` without one. */
    val to: Int?,
) {
    internal enum class Phase { RIDING, ARRIVED }
}
