package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.UiText
import io.proximi.gadkit.roundedInt
import kotlin.math.ceil
import kotlin.math.max

/** Route guidance as core reads it. The app maps the SDK's route guidance to this. */
internal data class RouteGuidance(
    val hasArrived: Boolean,
    val isOffRoute: Boolean,
    val distanceToManoeuvreMeters: Double,
    val manoeuvre: Manoeuvre?,
)

/** The next manoeuvre. Mirrors the SDK's manoeuvre kinds that the card distinguishes. */
internal sealed interface Manoeuvre {
    data class Turn(val direction: TurnDirection) : Manoeuvre

    /**
     * [featureType] is the level changer's feature type, e.g. `elevator`; [coordinate] is the level
     * changer. [GuidanceText.rideEnd] uses [fromLevel] and [coordinate].
     */
    data class LevelChange(
        val featureType: String,
        val toLevel: Double,
        val fromLevel: Double = toLevel,
        val coordinate: Coordinate? = null,
    ) : Manoeuvre

    data object Arrive : Manoeuvre

    /** Straight on, start, or any kind the card does not name. */
    data object Straight : Manoeuvre
}

internal enum class TurnDirection { LEFT, SHARP_LEFT, SLIGHT_LEFT, RIGHT, SHARP_RIGHT, SLIGHT_RIGHT }

/** Icon of the next manoeuvre. iOS SF Symbol in brackets. */
internal enum class GuidanceSymbol {
    /** `mappin.circle` */
    ARRIVED,

    /** `exclamationmark.triangle` */
    OFF_ROUTE,

    /** `arrow.turn.up.left` */
    TURN_LEFT,

    /** `arrow.up.left` */
    BEAR_LEFT,

    /** `arrow.turn.up.right` */
    TURN_RIGHT,

    /** `arrow.up.right` */
    BEAR_RIGHT,

    /** `arrow.up.arrow.down` */
    LEVEL_CHANGE,

    /** `arrow.up` */
    STRAIGHT,
}

/**
 * Text and symbols for the navigation card ("MAP Functionality Approach 02",
 * node 10889:108082): "Head straight for 70 m", "70 m · <1 min", "Take the lift to level 2".
 * Text is returned as string resource keys; the English is noted on each key.
 */
internal object GuidanceText {
    /** Walking speed for the time estimate, in metres per second. 1.2 m/s gives the design's "70 m · <1 min". */
    internal const val WALKING_SPEED: Double = 1.2

    /**
     * The instruction line. Keys:
     * `guidance_arrived` "You have arrived", `guidance_off_route` "You have left the route",
     * `guidance_turn_left` "Turn left in %d m", `guidance_bear_left` "Bear left in %d m",
     * `guidance_turn_right` "Turn right in %d m", `guidance_bear_right` "Bear right in %d m",
     * `guidance_take_level_changer` "Take the %1$s to level %2$d" (args: [levelChanger] text, level),
     * `guidance_arrive_in` "Arrive in %d m", `guidance_head_straight` "Head straight for %d m".
     *
     * [markers] are the route's level changes (the drawn route's markers), which give the floor a
     * ride ends on ([rideEnd]).
     */
    internal fun instruction(guidance: RouteGuidance, markers: List<LevelChangeMarker> = emptyList()): UiText {
        if (guidance.hasArrived) return UiText.Resource("guidance_arrived")
        if (guidance.isOffRoute) return UiText.Resource("guidance_off_route")
        val metres = guidance.distanceToManoeuvreMeters.roundedInt()
        return when (val manoeuvre = guidance.manoeuvre) {
            is Manoeuvre.Turn -> when (manoeuvre.direction) {
                TurnDirection.LEFT, TurnDirection.SHARP_LEFT -> UiText.Resource("guidance_turn_left", listOf(metres))
                TurnDirection.SLIGHT_LEFT -> UiText.Resource("guidance_bear_left", listOf(metres))
                TurnDirection.RIGHT, TurnDirection.SHARP_RIGHT -> UiText.Resource("guidance_turn_right", listOf(metres))
                TurnDirection.SLIGHT_RIGHT -> UiText.Resource("guidance_bear_right", listOf(metres))
            }
            is Manoeuvre.LevelChange -> rideText(
                manoeuvre.featureType,
                rideEnd(manoeuvre.featureType, manoeuvre.coordinate, manoeuvre.fromLevel, manoeuvre.toLevel, markers),
            )
            Manoeuvre.Arrive -> UiText.Resource("guidance_arrive_in", listOf(metres))
            Manoeuvre.Straight, null -> UiText.Resource("guidance_head_straight", listOf(metres))
        }
    }

    /** Remaining distance and time, shown as "70 m · <1 min". */
    internal data class Remaining(val metres: Int, val time: UiText)

    internal fun remaining(metres: Double): Remaining = Remaining(metres.roundedInt(), walkingTime(metres))

    /**
     * `guidance_less_than_one_minute` "<1 min" below one minute, otherwise
     * `guidance_minutes` "%d min" with whole minutes rounded up.
     */
    internal fun walkingTime(metres: Double): UiText {
        val seconds = max(0.0, metres) / WALKING_SPEED
        if (seconds < 60) return UiText.Resource("guidance_less_than_one_minute")
        return UiText.Resource("guidance_minutes", listOf(ceil(seconds / 60).toInt()))
    }

    internal fun symbol(guidance: RouteGuidance): GuidanceSymbol {
        if (guidance.hasArrived) return GuidanceSymbol.ARRIVED
        if (guidance.isOffRoute) return GuidanceSymbol.OFF_ROUTE
        return when (val manoeuvre = guidance.manoeuvre) {
            is Manoeuvre.Turn -> when (manoeuvre.direction) {
                TurnDirection.LEFT, TurnDirection.SHARP_LEFT -> GuidanceSymbol.TURN_LEFT
                TurnDirection.SLIGHT_LEFT -> GuidanceSymbol.BEAR_LEFT
                TurnDirection.RIGHT, TurnDirection.SHARP_RIGHT -> GuidanceSymbol.TURN_RIGHT
                TurnDirection.SLIGHT_RIGHT -> GuidanceSymbol.BEAR_RIGHT
            }
            is Manoeuvre.LevelChange -> GuidanceSymbol.LEVEL_CHANGE
            Manoeuvre.Arrive, Manoeuvre.Straight, null -> GuidanceSymbol.STRAIGHT
        }
    }

    /**
     * Visitor-facing name of a level changer feature type: `guidance_level_changer_lift` "lift",
     * `…_escalator` "escalator", `…_stairs` "stairs", `…_ramp` "ramp"; other types verbatim.
     */
    internal fun levelChanger(featureType: String): UiText = when (featureType) {
        "elevator" -> UiText.Resource("guidance_level_changer_lift")
        "escalator" -> UiText.Resource("guidance_level_changer_escalator")
        "staircase" -> UiText.Resource("guidance_level_changer_stairs")
        "ramp" -> UiText.Resource("guidance_level_changer_ramp")
        else -> UiText.Verbatim(featureType)
    }

    /** Floor chip label, e.g. "L1". Not localized. */
    internal fun floorLabel(level: Double): String = "L${level.roundedInt()}"

    /**
     * The card title while the next stop is on another floor: the route's first level change that
     * leaves the visitor's floor, e.g. "Take the stairs to level 3". A route from level 1 to level 4
     * can take the stairs to level 3 and then the lift. Without a level change on the route:
     * `visit_go_to_level` "Go to level %d". The key and arguments of a change are those of
     * [instruction], so the card can compare the two. Pure; covered by tests.
     */
    internal fun levelChangeTitle(markers: List<LevelChangeMarker>, visitorLevel: Double?, stopLevel: Int): UiText {
        val pending = markers.filter { !it.isCompleted }
        val fromHere = visitorLevel?.let { level -> pending.firstOrNull { it.fromLevel.roundedInt() == level.roundedInt() } }
        val change = fromHere ?: pending.firstOrNull() ?: return UiText.Resource("visit_go_to_level", listOf(stopLevel))
        val level = rideEnd(change.featureType, change.coordinate, change.fromLevel, change.toLevel, pending)
        return rideText(change.featureType, level)
    }

    /** `guidance_take_level_changer` "Take the %1$s to level %2$d", e.g. "Take the stairs to level 4". */
    internal fun rideText(featureType: String, level: Int): UiText =
        UiText.Resource("guidance_take_level_changer", listOf(levelChanger(featureType), level))

    /**
     * The floor a ride in one staircase or lift ends on. The SDK router splits a ride into one level
     * change per floor with paths near the staircase or lift: from level 1 to level 4 the stairs go
     * to level 3, then from level 3 to level 4, at the same coordinate (Monday "Instructions guide
     * first to level 3, then level 4", 2026-10-15). The level changes in [markers] that follow the
     * one from [from] to [to], with the same type and coordinate, are the same ride. Pure; covered
     * by tests.
     */
    internal fun rideEnd(featureType: String, coordinate: Coordinate?, from: Double, to: Double, markers: List<LevelChangeMarker>): Int {
        fun isSameChanger(marker: LevelChangeMarker) = marker.featureType == featureType && marker.coordinate == coordinate
        var end = to.roundedInt()
        val start = markers.indexOfFirst {
            isSameChanger(it) && it.fromLevel.roundedInt() == from.roundedInt() && it.toLevel.roundedInt() == end
        }
        if (start < 0) return end
        for (marker in markers.drop(start + 1)) {
            if (!isSameChanger(marker) || marker.fromLevel.roundedInt() != end) break
            end = marker.toLevel.roundedInt()
        }
        return end
    }
}

/** A level change of the drawn route (the map library's `RouteGeometry.Marker`). [coordinate] is the level changer. */
internal data class LevelChangeMarker(
    val featureType: String,
    val fromLevel: Double,
    val toLevel: Double,
    val isCompleted: Boolean,
    val coordinate: Coordinate,
)
