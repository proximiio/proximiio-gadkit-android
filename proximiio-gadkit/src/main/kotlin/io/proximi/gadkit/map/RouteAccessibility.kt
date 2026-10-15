package io.proximi.gadkit.map

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import io.proximi.sdk.wayfinding.RouteOptions

/**
 * The visitor's accessibility needs for routes, as iOS `RouteAccessibility`. The host app provides
 * them with [LocalRouteAccessibility], for example from its settings screen. `MuseumMapScreen` and
 * `VisitScreen` use them for every route they compute; a change re-routes the route on screen.
 *
 * Each need is an SDK avoidance (`RouteOptions.Avoidance`) and works only where the venue data
 * marks it:
 * - stairs: level changers with `type` `staircase`
 * - escalators: level changers with `type` `escalator`
 * - revolving doors: `door` point features with a `radius`; the route keeps that distance
 * - inaccessible paths: routing paths with `narrowPath: true`
 */
public data class RouteAccessibility(
    val avoidsStairs: Boolean = false,
    val avoidsEscalators: Boolean = false,
    val avoidsRevolvingDoors: Boolean = false,
    /** Paths the venue data marks as narrow (`narrowPath`). */
    val avoidsInaccessiblePaths: Boolean = false,
) {
    /** The SDK route options: the pedestrian profile with these avoidances. */
    public val routeOptions: RouteOptions
        get() = RouteOptions(
            profile = RouteOptions.Profile.PEDESTRIAN,
            avoid = buildSet {
                if (avoidsStairs) add(RouteOptions.Avoidance.STAIRCASES)
                if (avoidsEscalators) add(RouteOptions.Avoidance.ESCALATORS)
                if (avoidsRevolvingDoors) add(RouteOptions.Avoidance.REVOLVING_DOORS)
                if (avoidsInaccessiblePaths) add(RouteOptions.Avoidance.NARROW_PATHS)
            },
        )

    public companion object {
        /** No need set: routes may use every path. */
        public val NONE: RouteAccessibility = RouteAccessibility()
    }
}

/**
 * The accessibility needs that `MuseumMapScreen` and `VisitScreen` use for routes, as the iOS
 * modifier `routeAccessibility(_:)`. Default [RouteAccessibility.NONE]:
 *
 * ```
 * CompositionLocalProvider(LocalRouteAccessibility provides RouteAccessibility(avoidsStairs = true)) {
 *     VisitScreen(venue, wristband, journey, onClose = onClose)
 * }
 * ```
 */
public val LocalRouteAccessibility: ProvidableCompositionLocal<RouteAccessibility> = compositionLocalOf { RouteAccessibility.NONE }
