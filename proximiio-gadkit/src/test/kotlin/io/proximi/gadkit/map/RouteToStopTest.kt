package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.GadSettings
import io.proximi.gadkit.visit.GalleryFirstRouting
import io.proximi.map.core.FloorKey
import io.proximi.map.core.MapCoordinate
import io.proximi.map.live.JourneyRoutePoint
import io.proximi.map.live.JourneyRouting
import io.proximi.sdk.core.geo.GeoMath
import io.proximi.sdk.core.model.ProximiioCoordinate
import io.proximi.sdk.wayfinding.ComputedRoute
import io.proximi.sdk.wayfinding.RouteInstruction
import io.proximi.sdk.wayfinding.RouteOptions
import io.proximi.sdk.wayfinding.RouteResult
import io.proximi.sdk.wayfinding.RouteSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Port of ProximiioGADKit `RouteToStopTests` (iOS 1.36.0). */
class RouteToStopTest {
    /** User, 2026-11-09: the route went only as far as the path, short of the artwork. */
    @Test
    fun theRouteGoesOnFromThePathToTheStop() {
        val route = route()
        val extended = RouteToStop.extended(route, ARTWORK, maximumMeters = 15.0)
        val added = GeoMath.haversineDistance(END, ARTWORK)
        assertEquals(2.2, added, 0.1)
        assertEquals(listOf(START, END, ARTWORK), extended.coordinates)
        assertEquals(RouteSegment(END, ARTWORK, added), extended.segments.last())
        assertEquals(route.distanceMeters + added, extended.distanceMeters, 1e-9)
        assertTrue(extended.durationSeconds > route.durationSeconds)
        assertEquals(1.0, extended.level)
        val arrive = extended.instructions.last()
        assertEquals(RouteInstruction.Kind.Arrive, arrive.kind)
        assertEquals(ARTWORK, arrive.coordinate)
        assertEquals(extended.distanceMeters, arrive.distanceMeters, 1e-9)
        // The instructions add up to the route, so the map's remaining time stays exact.
        assertEquals(extended.durationSeconds, extended.instructions.sumOf { it.durationSeconds }, 1e-9)
    }

    @Test
    fun aStopOnThePathOrTooFarFromItLeavesTheRoute() {
        val route = route()
        assertSame(route, RouteToStop.extended(route, END, maximumMeters = 15.0))
        assertSame(route, RouteToStop.extended(route, ARTWORK, maximumMeters = 2.0))
        assertSame(route, RouteToStop.extended(route, ARTWORK, maximumMeters = 0.0))
    }

    @Test
    fun theStandardSettingExtendsEveryRoute() {
        // The SDK routes to a stop at most 15 m from a path.
        assertEquals(15.0, GadSettings.STANDARD.routeToStopMaximumMeters)
    }

    internal companion object {
        val START = ProximiioCoordinate(24.5, 54.4)

        /** About 10.1 m east of [START]: where the stop projects onto the path. */
        val END = ProximiioCoordinate(24.5, 54.4001)

        /** About 2.2 m north of [END]. */
        val ARTWORK = ProximiioCoordinate(24.50002, 54.4001)

        /** A one-segment route from [from] to [to], as the SDK returns it. */
        fun route(from: ProximiioCoordinate = START, to: ProximiioCoordinate = END, level: Double = 1.0): ComputedRoute {
            val meters = GeoMath.haversineDistance(from, to)
            return ComputedRoute(
                coordinates = listOf(from, to),
                distanceMeters = meters,
                segments = listOf(RouteSegment(from, to, meters)),
                level = level,
                levelChanges = emptyList(),
                instructions = listOf(
                    RouteInstruction(kind = RouteInstruction.Kind.Start, coordinate = from, distanceMeters = 0.0, level = level),
                    RouteInstruction(kind = RouteInstruction.Kind.Arrive, coordinate = to, distanceMeters = meters, level = level),
                ),
            )
        }
    }
}

/** Port of ProximiioGADKit `GalleryFirstRouteToStopTests` (iOS 1.36.0). */
class GalleryFirstRouteToStopTest {
    /** A gallery of about 22 × 22 m north of the path at latitude 24.5. */
    private val destination = GalleryDestination(
        Gallery(
            "e1d2cd21", "G303 Language", 3.0,
            listOf(Coordinate(24.5001, 54.4), Coordinate(24.5001, 54.4002), Coordinate(24.5003, 54.4002), Coordinate(24.5003, 54.4)),
        ),
        entry = Coordinate(24.5002, 54.4001),
        placeId = "poi-g303",
    )
    private val artwork = MapCoordinate(24.50025, 54.40015)
    private val origin = MapCoordinate(24.5, 54.4)

    private fun leg(galleryFirst: Boolean): ComputedRoute = runBlocking {
        val routing = GalleryFirstRouting(PathRouting(), routeToStopMaximumMeters = 15.0)
        routing.update(galleryFirst, mapOf(GalleryFirstRouting.Key(artwork) to destination))
        val result = routing.route(origin, FloorKey(3.0), artwork, FloorKey(3.0), RouteOptions())
        (result as RouteResult.Route).route
    }

    @Test
    fun aLegToAnArtworkEndsAtTheArtwork() {
        val route = leg(galleryFirst = false)
        assertEquals(ProximiioCoordinate(artwork.latitude, artwork.longitude), route.coordinates.last())
        assertEquals(3, route.coordinates.size)
    }

    @Test
    fun aLegToAGalleryEndsOnThePath() {
        val route = leg(galleryFirst = true)
        assertEquals(PathRouting.onPath(MapCoordinate(destination.entry.latitude, destination.entry.longitude)), route.coordinates.last())
        assertEquals(2, route.coordinates.size)
    }

    @Test
    fun theLegWithoutOptionsEndsAtTheArtworkToo() = runBlocking {
        val routing = GalleryFirstRouting(PathRouting(), routeToStopMaximumMeters = 15.0)
        val route = routing.route(origin, FloorKey(3.0), artwork, FloorKey(3.0))
        assertEquals(ProximiioCoordinate(artwork.latitude, artwork.longitude), route?.coordinates?.last())
    }
}

/** Routes every leg to a point on a path 2.2 m south of its destination. */
private class PathRouting : JourneyRouting {
    private fun route(origin: MapCoordinate, destination: MapCoordinate): ComputedRoute =
        RouteToStopTest.route(from = ProximiioCoordinate(origin.latitude, origin.longitude), to = onPath(destination), level = 3.0)

    override suspend fun route(origin: MapCoordinate, originFloor: FloorKey, destination: MapCoordinate, destinationFloor: FloorKey): ComputedRoute =
        route(origin, destination)

    override suspend fun route(origin: MapCoordinate, originFloor: FloorKey, destination: MapCoordinate, destinationFloor: FloorKey, options: RouteOptions): RouteResult =
        RouteResult.Route(route(origin, destination))

    override suspend fun durations(origins: List<JourneyRoutePoint>, destinations: List<JourneyRoutePoint>): List<List<Double?>> = emptyList()

    override suspend fun durations(origins: List<JourneyRoutePoint>, destinations: List<JourneyRoutePoint>, options: RouteOptions): List<List<Double?>> = emptyList()

    companion object {
        fun onPath(destination: MapCoordinate) = ProximiioCoordinate(destination.latitude - 0.00002, destination.longitude)
    }
}
