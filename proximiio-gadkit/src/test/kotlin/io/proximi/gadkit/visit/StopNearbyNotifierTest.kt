package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.LocalNotice
import io.proximi.map.core.JourneyStop
import io.proximi.map.core.MapCoordinate
import io.proximi.map.core.VenuePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `StopNearbyNotifierTests` and `VenueLevelTests.aStopOnAMezzanineIsNotNearbyFromTheLevelAbove`. */
class StopNearbyNotifierTest {
    private val stop = Coordinate(24.464, 54.329)

    /** [meters] north of [stop]. One degree of latitude is about 111 km. */
    private fun north(meters: Double) = Coordinate(stop.latitude + meters / 111_000, stop.longitude)

    @Test
    fun nearbyIsLessThanFiveMetresOnTheSameLevel() {
        assertTrue(StopNearbyNotifier.isNearby(north(4.0), 1.0, stop, 1.0))
        assertFalse(StopNearbyNotifier.isNearby(north(6.0), 1.0, stop, 1.0))
        assertFalse(StopNearbyNotifier.isNearby(north(1.0), 2.0, stop, 1.0))
    }

    @Test
    fun anUnknownLevelMatchesAnyLevel() {
        assertTrue(StopNearbyNotifier.isNearby(north(1.0), null, stop, 1.0))
        assertTrue(StopNearbyNotifier.isNearby(north(1.0), 1.0, stop, null))
    }

    @Test
    fun theRouteDistanceWinsOverTheStraightLine() {
        // A stop 2 m away behind a wall, 12 m to walk.
        assertFalse(StopNearbyNotifier.isNearby(north(2.0), 1.0, stop, 1.0, routeMeters = 12.0))
        // A stop 3 m away in a straight line, 4 m to walk.
        assertTrue(StopNearbyNotifier.isNearby(north(3.0), 1.0, stop, 1.0, routeMeters = 4.0))
        assertFalse(StopNearbyNotifier.isNearby(north(1.0), 2.0, stop, 1.0, routeMeters = 1.0))
    }

    @Test
    fun aStopOnAMezzanineIsNotNearbyFromTheLevelAbove() {
        assertTrue(StopNearbyNotifier.isNearby(stop, 1.5, stop, 1.5))
        assertFalse(StopNearbyNotifier.isNearby(stop, 2.0, stop, 1.5))
    }

    @Test
    fun aStopIsReportedOncePerVisit() {
        val posted = mutableListOf<LocalNotice>()
        val notifier = StopNearbyNotifier { posted += it }
        val artwork = JourneyStop(id = "a", title = "Artwork", coordinate = MapCoordinate(stop.latitude, stop.longitude))
        val here = north(1.0)
        val position = VenuePosition(MapCoordinate(here.latitude, here.longitude), horizontalAccuracy = 2.0, timestampMillis = 0)
        assertEquals(artwork, notifier.update(position, artwork, guidance = null))
        assertNull(notifier.update(position, artwork, guidance = null))
        assertEquals(listOf("stop-nearby-a"), posted.map { it.tag })
        assertEquals("journey", posted.single().group)
    }
}
