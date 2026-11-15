package io.proximi.gadkit.map

import io.proximi.sdk.core.geo.GeoMath
import io.proximi.sdk.core.model.ProximiioCoordinate
import io.proximi.sdk.wayfinding.ComputedRoute
import io.proximi.sdk.wayfinding.RouteCostModel
import io.proximi.sdk.wayfinding.RouteInstruction
import io.proximi.sdk.wayfinding.RouteSegment

/**
 * The SDK's route ends where the destination projects onto the path network. A stop beside the path,
 * such as an artwork on a wall, is a few metres further. [extended] adds the straight segment from the
 * route's end to the stop (user, 2026-11-09; iOS `RouteToStop.swift`, 1.36.0). The journey legs to a
 * stop ([io.proximi.gadkit.visit.GalleryFirstRouting]) and the "Go here" routes of the maps end at the
 * stop. A leg to a gallery's POI (gallery first) ends on the path.
 *
 * The route also starts where the origin projects onto the path network. [extendedFrom] adds the
 * straight segment from the origin to the route's start, once per computed route, so the line starts at
 * the visitor and does not move with later positions (PM, Monday "Routing Tongari-Kun -> Goroka man",
 * 2026-11-15; iOS 1.44.0).
 */
internal object RouteToStop {
    /** Below this distance the stop or the origin counts as on the path: 1 cm. */
    private const val MINIMUM_METERS = 0.01

    /**
     * [route] with a first segment from [origin] to its start, when [origin] is at least 1 cm and at
     * most [maximumMeters] from the start. The distance and the walking time include the segment. The
     * start instruction moves to [origin], and the instruction after it takes the segment's distance and
     * time. Otherwise [route] itself. Pure; covered by tests.
     */
    fun extendedFrom(route: ComputedRoute, origin: ProximiioCoordinate, maximumMeters: Double): ComputedRoute {
        val start = route.coordinates.firstOrNull() ?: return route
        val meters = GeoMath.haversineDistance(origin, start)
        if (meters < MINIMUM_METERS || meters > maximumMeters) return route
        val seconds = RouteCostModel().walkingSeconds(meters)
        val steps = route.instructions.toMutableList()
        val first = steps.firstOrNull()
        if (first?.kind == RouteInstruction.Kind.Start) {
            steps[0] = first.copy(coordinate = origin)
            if (steps.size > 1) {
                val next = steps[1]
                steps[1] = next.copy(distanceMeters = next.distanceMeters + meters, durationSeconds = next.durationSeconds + seconds)
            }
        }
        return route.copy(
            coordinates = listOf(origin) + route.coordinates,
            distanceMeters = route.distanceMeters + meters,
            segments = listOf(RouteSegment(start = origin, end = start, distanceMeters = meters)) + route.segments,
            instructions = steps,
            durationSeconds = route.durationSeconds + seconds,
        )
    }

    /**
     * [route] with a last segment from its end to [stop], when [stop] is at least 1 cm and at most
     * [maximumMeters] from the end. The distance, the walking time and the arrive instruction include
     * the segment, and the arrive instruction moves to [stop]. Otherwise [route] itself. Pure; covered
     * by tests.
     */
    fun extended(route: ComputedRoute, stop: ProximiioCoordinate, maximumMeters: Double): ComputedRoute {
        val end = route.coordinates.lastOrNull() ?: return route
        val meters = GeoMath.haversineDistance(end, stop)
        if (meters < MINIMUM_METERS || meters > maximumMeters) return route
        val seconds = RouteCostModel().walkingSeconds(meters)
        val arrive = route.instructions.lastOrNull()
        val instructions = if (arrive?.kind == RouteInstruction.Kind.Arrive) {
            route.instructions.dropLast(1) + RouteInstruction(
                kind = RouteInstruction.Kind.Arrive,
                coordinate = stop,
                distanceMeters = arrive.distanceMeters + meters,
                level = arrive.level,
                durationSeconds = arrive.durationSeconds + seconds,
            )
        } else {
            route.instructions
        }
        return route.copy(
            coordinates = route.coordinates + stop,
            distanceMeters = route.distanceMeters + meters,
            segments = route.segments + RouteSegment(start = end, end = stop, distanceMeters = meters),
            instructions = instructions,
            durationSeconds = route.durationSeconds + seconds,
        )
    }
}
