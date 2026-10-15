package io.proximi.gadkit.map

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import io.proximi.gadkit.R
import io.proximi.gadkit.UiText
import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.map.GuidanceSymbol
import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.map.Manoeuvre
import io.proximi.gadkit.map.RouteGuidance
import io.proximi.gadkit.map.TurnDirection
import io.proximi.gadkit.designsystem.GadIcon
import io.proximi.gadkit.ui.resolve
import io.proximi.map.core.RouteManoeuvre
import io.proximi.map.core.RouteGuidance as MapRouteGuidance

/** String resources of the keys core's map rules return ([GuidanceText], [AmenityKind]). */
private val strings: Map<String, Int> = mapOf(
    "guidance_arrived" to R.string.you_have_arrived,
    "guidance_off_route" to R.string.you_have_left_the_route,
    "guidance_turn_left" to R.string.turn_left_in_n_m,
    "guidance_bear_left" to R.string.bear_left_in_n_m,
    "guidance_turn_right" to R.string.turn_right_in_n_m,
    "guidance_bear_right" to R.string.bear_right_in_n_m,
    "guidance_take_level_changer" to R.string.take_the_x_to_level_n,
    "guidance_arrive_in" to R.string.arrive_in_n_m,
    "guidance_head_straight" to R.string.head_straight_for_n_m,
    "guidance_less_than_one_minute" to R.string.s_1_min,
    "guidance_level_changer_lift" to R.string.lift,
    "guidance_level_changer_escalator" to R.string.escalator,
    "guidance_level_changer_stairs" to R.string.stairs_2,
    "guidance_level_changer_ramp" to R.string.ramp,
    "amenity_womens_restroom" to R.string.women_s_restroom,
    "amenity_mens_restroom" to R.string.men_s_restroom,
    "amenity_cafe" to R.string.caf,
    "amenity_help_desk" to R.string.help_desk,
    "amenity_prayer_room" to R.string.prayer_room,
    "amenity_stairs" to R.string.stairs,
    "amenity_elevator" to R.string.elevator,
    "amenity_museum_shop" to R.string.museum_shop,
)

/** Keys whose first argument is the plural count. */
private val plurals: Map<String, Int> = mapOf(
    "guidance_minutes" to R.plurals.n_min,
)

/**
 * The text of a map key. Arguments that are [UiText] themselves (the level changer of
 * "Take the lift to level 2") are resolved first. Other keys go to the app's table ([resolve]).
 */
internal fun UiText.mapText(resources: Resources): String = when (this) {
    is UiText.Verbatim -> text
    is UiText.Resource -> {
        val args = args.map { if (it is UiText) it.mapText(resources) else it }.toTypedArray()
        @PluralsRes val plural = plurals[key]
        @StringRes val string = strings[key]
        when {
            plural != null -> resources.getQuantityString(plural, (args.first() as Number).toInt(), *args)
            string != null -> resources.getString(string, *args)
            else -> UiText.Resource(key, args.toList()).resolve(resources)
        }
    }
}

@Composable
internal fun UiText.mapText(): String = mapText(LocalResources.current)

/** Remaining distance and time, e.g. "70 m · <1 min" (iOS `GuidanceText.remaining`). */
internal fun remainingText(metres: Double, resources: Resources): String {
    val remaining = GuidanceText.remaining(metres)
    return resources.getString(R.string.n_m, remaining.metres) + " · " + remaining.time.mapText(resources)
}

/** The map's route guidance as core's guidance rules read it. */
internal fun MapRouteGuidance.toCore(): RouteGuidance = RouteGuidance(
    hasArrived = hasArrived,
    isOffRoute = isOffRoute,
    distanceToManoeuvreMeters = distanceToManoeuvreMeters,
    manoeuvre = manoeuvre?.kind?.toCore(),
)

private fun RouteManoeuvre.Kind.toCore(): Manoeuvre = when (this) {
    RouteManoeuvre.Kind.TurnLeft -> Manoeuvre.Turn(TurnDirection.LEFT)
    RouteManoeuvre.Kind.TurnSharpLeft -> Manoeuvre.Turn(TurnDirection.SHARP_LEFT)
    RouteManoeuvre.Kind.TurnSlightLeft -> Manoeuvre.Turn(TurnDirection.SLIGHT_LEFT)
    RouteManoeuvre.Kind.TurnRight -> Manoeuvre.Turn(TurnDirection.RIGHT)
    RouteManoeuvre.Kind.TurnSharpRight -> Manoeuvre.Turn(TurnDirection.SHARP_RIGHT)
    RouteManoeuvre.Kind.TurnSlightRight -> Manoeuvre.Turn(TurnDirection.SLIGHT_RIGHT)
    is RouteManoeuvre.Kind.LevelChange -> Manoeuvre.LevelChange(change.featureType, change.toLevel)
    RouteManoeuvre.Kind.Arrive -> Manoeuvre.Arrive
    RouteManoeuvre.Kind.Start, RouteManoeuvre.Kind.ContinueStraight -> Manoeuvre.Straight
}

/** Drawable of a guidance symbol (iOS SF Symbol in [GuidanceSymbol]'s KDoc). */
@get:DrawableRes
internal val GuidanceSymbol.drawable: Int
    get() = when (this) {
        GuidanceSymbol.ARRIVED -> R.drawable.ic_map_arrived
        GuidanceSymbol.OFF_ROUTE -> R.drawable.ic_map_off_route
        GuidanceSymbol.TURN_LEFT -> R.drawable.ic_map_turn_left
        GuidanceSymbol.BEAR_LEFT -> R.drawable.ic_map_bear_left
        GuidanceSymbol.TURN_RIGHT -> R.drawable.ic_map_turn_right
        GuidanceSymbol.BEAR_RIGHT -> R.drawable.ic_map_bear_right
        GuidanceSymbol.LEVEL_CHANGE -> R.drawable.ic_map_level_change
        GuidanceSymbol.STRAIGHT -> R.drawable.ic_map_straight
    }

/** The design icon of an amenity kind (iOS `GADIcon`). */
internal val AmenityKind.gadIcon: GadIcon
    get() = when (this) {
        AmenityKind.WOMENS_RESTROOM -> GadIcon.FemaleToilet
        AmenityKind.MENS_RESTROOM -> GadIcon.MaleToilet
        AmenityKind.CAFE -> GadIcon.Cafe
        AmenityKind.HELP_DESK -> GadIcon.Info
        AmenityKind.PRAYER_ROOM -> GadIcon.PrayerRoom
        AmenityKind.STAIRS -> GadIcon.Stairs
        AmenityKind.ELEVATOR -> GadIcon.Elevator
        AmenityKind.MUSEUM_SHOP -> GadIcon.Shop
    }
