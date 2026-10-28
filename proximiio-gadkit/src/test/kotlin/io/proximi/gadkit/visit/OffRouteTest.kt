package io.proximi.gadkit.visit

import io.proximi.gadkit.GadSettings
import io.proximi.map.core.FloorKey
import io.proximi.map.core.JourneyEvent
import io.proximi.map.core.MapCoordinate
import io.proximi.map.core.RouteGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Port of ProximiioGADKit `OffRouteTests`: the wrong floor counts as off the route, the off-route
 * warning's time, and the reports a paused journey drops (PM, Monday 2026-10-27; Nagarro, 2026-10-28).
 */
class OffRouteTest {
    private fun leg(level: Double, completed: Boolean = false, order: Int = 0) = RouteGeometry.Leg(
        floor = FloorKey(level),
        points = listOf(MapCoordinate(24.5, 54.4), MapCoordinate(24.5001, 54.4)),
        isCompleted = completed,
        order = order,
    )

    /** Monday "Wrong floor to trigger off-route and re-routing": on floor 2 with the route on floor 1. */
    @Test
    fun aFloorTheRouteAheadDoesNotTakeIsOffTheRoute() {
        val legs = listOf(leg(1.0, order = 0))
        assertTrue(VisitController.isOnRouteFloor(1.0, legs))
        assertFalse(VisitController.isOnRouteFloor(2.0, legs))
    }

    /** A ride from level 1 to level 4: both floors are on the route, the floors in between are not. */
    @Test
    fun bothEndsOfARideAreOnTheRoute() {
        val legs = listOf(leg(1.0, order = 0), leg(4.0, order = 1))
        assertTrue(VisitController.isOnRouteFloor(1.0, legs))
        assertTrue(VisitController.isOnRouteFloor(4.0, legs))
        assertFalse(VisitController.isOnRouteFloor(2.0, legs))
    }

    /** A floor the visitor has left behind is no longer on the route. */
    @Test
    fun aCompletedLegIsNotOnTheRoute() {
        val legs = listOf(leg(1.0, completed = true, order = 0), leg(4.0, order = 1))
        assertFalse(VisitController.isOnRouteFloor(1.0, legs))
        assertTrue(VisitController.isOnRouteFloor(4.0, legs))
    }

    /** No route still to walk: no wrong floor. */
    @Test
    fun noRouteAheadIsNoWrongFloor() {
        assertTrue(VisitController.isOnRouteFloor(2.0, emptyList()))
        assertTrue(VisitController.isOnRouteFloor(2.0, listOf(leg(1.0, completed = true))))
    }

    /** Monday "Personalized tour: off-route and re-route behaviour", 2026-10-27: the warning shows for 10 s; the wrong floor counts after 10 s. */
    @Test
    fun theWarningShowsFor10Seconds() {
        assertEquals(10.seconds, GadSettings.STANDARD.offRouteWarningDuration)
        assertEquals(10.seconds, GadSettings.STANDARD.wrongFloorAfter)
    }

    /** A paused journey passes on no report of leaving or rejoining the route (Nagarro, 2026-10-28). */
    @Test
    fun aPausedJourneyDropsTheRouteDeviationReports() {
        assertTrue(VisitViewModel.isRouteDeviation(JourneyEvent.LeftRoute(13.0)))
        assertTrue(VisitViewModel.isRouteDeviation(JourneyEvent.FarFromRoute(31.0)))
        assertTrue(VisitViewModel.isRouteDeviation(JourneyEvent.OffRouteTooLong(60.0)))
        assertTrue(VisitViewModel.isRouteDeviation(JourneyEvent.ReturnedToRoute))
        assertFalse(VisitViewModel.isRouteDeviation(JourneyEvent.JourneyFinished))
    }
}
