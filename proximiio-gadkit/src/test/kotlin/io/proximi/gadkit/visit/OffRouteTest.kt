package io.proximi.gadkit.visit

import io.proximi.gadkit.GadSettings
import io.proximi.gadkit.map.MapStyle
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

    /** Monday "Navigation to a gallery", 2026-10-27: outside every gallery the numbers of the artworks in galleries are hidden. A curated tour shows them. */
    @Test
    fun artworkMarkersShowInAGalleryWithGalleryFirst() {
        fun shows(galleryFirst: Boolean, ahead: Boolean = false, inGallery: Boolean, activeInGallery: Boolean = true) =
            VisitViewModel.showsArtworkMarkers(galleryFirst, ahead, inGallery, activeInGallery)
        assertTrue(shows(true, inGallery = true))
        assertFalse(shows(true, inGallery = false))
        assertFalse(shows(true, ahead = true, inGallery = true))
        // The active artwork is outside every gallery: its number shows.
        assertTrue(shows(true, inGallery = false, activeInGallery = false))
        assertTrue(shows(false, ahead = true, inGallery = false))
    }

    /** The markers are hidden with an invisible style, never `null`: the detour keeps its marker. */
    @Test
    fun theHiddenStyleKeepsTheDetour() {
        val hidden = MapStyle.journeyStopsHidden
        assertEquals(0.0, hidden.pending.opacity)
        assertEquals(0.0, hidden.active.opacity)
        assertEquals(0.0, hidden.done.opacity)
        assertEquals(0.0, hidden.passed.opacity)
        assertEquals(1.0, hidden.detour.opacity)
        assertTrue(hidden != MapStyle.journeyStops)
    }

    /**
     * The warning follows the leg to the current destination: 13 m off it warns also where a later
     * leg passes, once per episode; back within 8 m the episode ends (PM's log of 2026-10-28, 22:39 UTC).
     */
    @Test
    fun theLegOffRouteWarnsOncePerEpisode() {
        fun deviation(isOffRoute: Boolean, meters: Double, isEpisode: Boolean, isLegEpisode: Boolean) =
            VisitController.legDeviation(isOffRoute, meters, threshold = 12.0, isEpisode = isEpisode, isLegEpisode = isLegEpisode)
        assertEquals(VisitController.LegDeviation.WARNS, deviation(true, 13.0, isEpisode = false, isLegEpisode = false))
        assertEquals(VisitController.LegDeviation.NONE, deviation(true, 40.0, isEpisode = true, isLegEpisode = true))
        assertEquals(VisitController.LegDeviation.NONE, deviation(false, 10.0, isEpisode = true, isLegEpisode = true))
        assertEquals(VisitController.LegDeviation.ENDS, deviation(false, 7.0, isEpisode = true, isLegEpisode = true))
        // An episode of the whole journey ends with the navigator's `ReturnedToRoute`, not here.
        assertEquals(VisitController.LegDeviation.NONE, deviation(false, 2.0, isEpisode = true, isLegEpisode = false))
    }
}
