package io.proximi.gadkit.map

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import io.proximi.sdk.wayfinding.RouteOptions

/**
 * The visitor's accessibility needs for routes, as iOS `RouteAccessibility`. The host app sets
 * them in `ProximiMap.routeAccessibility`, for example from its settings screen. The museum map and
 * the journey use them for every route they compute; a change re-routes the route on screen.
 *
 * The maps also have an accessible routes button (a wheelchair, PM 2026-10-14). While it is on,
 * routes avoid all four, whatever the host app set. Its state is kept on the device, for both maps
 * and later launches.
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
    internal val routeOptions: RouteOptions
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

        /** Every need set: the accessible routes button. */
        public val ACCESSIBLE: RouteAccessibility =
            RouteAccessibility(avoidsStairs = true, avoidsEscalators = true, avoidsRevolvingDoors = true, avoidsInaccessiblePaths = true)

        /** The shared preferences file of the accessible routes button. */
        internal const val PREFERENCES_FILE: String = "proximiio_gadkit"

        /** The Boolean of the accessible routes button in [PREFERENCES_FILE]. */
        internal const val ACCESSIBLE_ROUTES_KEY: String = "ProximiioGADKit.accessibleRoutes"

        /** [ACCESSIBLE] while the button is on, else the host app's needs. Pure; covered by tests. */
        internal fun effective(hostNeeds: RouteAccessibility, accessibleRoutes: Boolean): RouteAccessibility =
            if (accessibleRoutes) ACCESSIBLE else hostNeeds
    }
}

/**
 * The accessibility needs that `MuseumMapScreen` and `VisitScreen` use for routes, as the iOS
 * modifier `routeAccessibility(_:)`. `ProximiMapHost` provides `ProximiMap.routeAccessibility`.
 * Default [RouteAccessibility.NONE].
 */
internal val LocalRouteAccessibility: ProvidableCompositionLocal<RouteAccessibility> = compositionLocalOf { RouteAccessibility.NONE }
