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
    /** The line from the blue dot to the route: up to the maximum length, and not without the setting (iOS 1.65.0, 1.65.1). */
    @Test
    fun theRouteConnectorHasAMaximumLength() {
        val off = GadSettings()
        assertFalse(off.connectsRouteToPosition)
        assertEquals(6.0, off.routeConnectorMaximumMeters)
        assertFalse(VisitViewModel.connectsRoute(0.5, off))
        val on = off.copy(connectsRouteToPosition = true)
        assertTrue(VisitViewModel.connectsRoute(0.5, on))
        assertTrue(VisitViewModel.connectsRoute(6.0, on))
        assertFalse(VisitViewModel.connectsRoute(6.1, on))
    }

    /** The defaults the GAD PM changed on 2026-11-20 (iOS 1.64.0). */
    @Test
    fun theDefaultsOfTheTwentiethOfNovember() {
        val settings = GadSettings()
        assertTrue(settings.skipsAtSwipe)
        assertEquals(20.0, settings.galleryFollowZoom)
        assertFalse(settings.shapesGalleryLabelsByType)
        assertEquals(19.0, settings.followZoom)
    }

    /** A gallery change takes the follow again only for a free camera of a running journey (GAD PM, 2026-11-20; iOS 1.63.0). */
    @Test
    fun aGalleryChangeTakesTheFollowAgain() {
        val settings = GadSettings()
        val follows = { s: GadSettings, following: Boolean, paused: Boolean, guide: Boolean, position: Boolean ->
            VisitViewModel.followsAgain(s, isFollowing = following, isPaused = paused, isFloorGuideActive = guide, hasPosition = position)
        }
        assertTrue(follows(settings, false, false, false, true))
        assertFalse(follows(settings, true, false, false, true))
        assertFalse(follows(settings, false, true, false, true))
        assertFalse(follows(settings, false, false, true, true))
        assertFalse(follows(settings, false, false, false, false))
        assertFalse(follows(settings.copy(followsAgainAtGalleryChange = false), false, false, false, true))
    }

    /**
     * In the open area the order from the visitor takes the place of the journey's order when it
     * saves `openAreaOrderMinimumMeters` by the route (GAD PM, 2026-11-20; iOS 1.61.1).
     */
    @Test
    fun theOpenAreaOrdersAgainWhenAnotherGalleryIsClearlyNearer() {
        val settings = GadSettings()
        assertTrue(VisitController.ordersInOpenArea(15.0, settings))
        assertTrue(VisitController.ordersInOpenArea(40.0, settings))
        assertFalse(VisitController.ordersInOpenArea(14.9, settings))
        assertTrue(VisitController.ordersInOpenArea(6.0, settings.copy(openAreaOrderMinimumMeters = 5.0)))
        assertFalse(VisitController.ordersInOpenArea(100.0, settings.copy(ordersInOpenArea = false)))
    }

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

    /** The markers are hidden with an invisible style, never `null`: the detour and the visited artworks keep their markers. */
    @Test
    fun theHiddenStyleKeepsTheDetour() {
        val hidden = MapStyle.journeyStopsHidden
        assertEquals(0.0, hidden.pending.opacity)
        assertEquals(0.0, hidden.active.opacity)
        assertEquals(1.0, hidden.done.opacity)
        assertEquals(1.0, hidden.passed.opacity)
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

    /** iOS 1.50.2: without the off-route prompt the leg is drawn again from a visitor who is off it. */
    @Test
    fun offTheLegWithoutTheWarningEveryJourneyIsRoutedAgain() {
        assertTrue(VisitController.reroutesOffTheLeg(asksApp = false, keepsOrder = false))
        assertTrue(VisitController.reroutesOffTheLeg(asksApp = false, keepsOrder = true))
        assertTrue(VisitController.reroutesOffTheLeg(asksApp = true, keepsOrder = true))
        assertFalse(VisitController.reroutesOffTheLeg(asksApp = true, keepsOrder = false))
    }

    /** iOS 1.51.0: in the gallery of the current artwork the leg is drawn again 3 m off it, at most every 3 s. */
    @Test
    fun inTheGalleryTheLegIsDrawnAgainBeyondThreeMetresEveryThreeSeconds() {
        val settings = io.proximi.gadkit.GadSettings.STANDARD
        assertTrue(settings.refreshesRouteInGallery)
        assertEquals(3.0, settings.galleryRouteRefreshMeters)
        assertEquals(3_000, settings.galleryRouteRefreshSeconds.inWholeMilliseconds)
        assertTrue(VisitController.refreshesGalleryRoute(3.1, 3_000, settings))
        assertFalse(VisitController.refreshesGalleryRoute(3.0, 60_000, settings))
        assertFalse(VisitController.refreshesGalleryRoute(8.0, 2_999, settings))
        assertFalse(VisitController.refreshesGalleryRoute(8.0, 60_000, settings.copy(refreshesRouteInGallery = false)))
        assertTrue(VisitController.refreshesGalleryRoute(8.0, 3_000, settings.copy(galleryRouteRefreshMeters = 7.0)))
    }

    /** iOS 1.52.0: the order at a gallery entry is checked after the visitor was in the gallery for 3 s. */
    @Test
    fun theGalleryEntryIsCheckedAfterTheDelay() {
        val settings = io.proximi.gadkit.GadSettings.STANDARD
        assertTrue(settings.ordersAtGalleryEntry)
        assertFalse(VisitController.checksGalleryEntry(2_900, settings))
        assertTrue(VisitController.checksGalleryEntry(3_000, settings))
        assertTrue(VisitController.checksGalleryEntry(0, settings.copy(galleryEntryOrderSeconds = kotlin.time.Duration.ZERO)))
        assertFalse(VisitController.checksGalleryEntry(60_000, settings.copy(ordersAtGalleryEntry = false)))
    }

    private fun proposal(order: List<String>, current: List<String>, improves: Boolean, pinned: List<String> = emptyList()) =
        io.proximi.map.core.JourneyOrderProposal(
            order = order,
            currentOrder = current,
            pinnedStopIds = pinned,
            currentMeters = 100.0,
            proposedMeters = if (improves) 60.0 else 100.0,
            liveStopId = current.firstOrNull(),
        )

    /** iOS 1.52.0, the cases of its test: the artworks of the visitor's gallery go first. */
    @Test
    fun theArtworksOfTheVisitorsGalleryGoFirst() {
        val current = listOf("a104", "b101", "c104", "d101")
        // No improvement by walking time: the journey's order, the gallery's artworks first.
        val same = proposal(current, current, improves = false)
        assertTrue(!same.isImprovement)
        assertEquals(listOf("b101", "d101", "a104", "c104"), VisitController.galleryFirst(same, setOf("b101", "d101"))?.order)
        // An improvement: the map's order, the gallery's artworks first.
        val better = proposal(listOf("c104", "d101", "a104", "b101"), current, improves = true)
        assertTrue(better.isImprovement)
        assertEquals(listOf("d101", "b101", "c104", "a104"), VisitController.galleryFirst(better, setOf("b101", "d101"))?.order)
        // The gallery's artworks are first already: nothing to apply.
        assertEquals(null, VisitController.galleryFirst(same, setOf("a104")))
        // The visitor is in no gallery: the map's order when it is an improvement.
        assertEquals(null, VisitController.galleryFirst(same, emptySet()))
        assertEquals(better, VisitController.galleryFirst(better, emptySet()))
        // Pinned stops keep the map's order.
        val pinned = proposal(listOf("c104", "d101", "a104", "b101"), current, improves = true, pinned = listOf("a104"))
        assertEquals(pinned, VisitController.galleryFirst(pinned, setOf("b101", "d101")))
    }

    /** iOS 1.53.0: another artwork becomes the stop only when it is 3 m nearer, or in the visitor's gallery. */
    @Test
    fun anotherArtworkBecomesTheStopOnlyWhenItIsClearlyNearer() {
        val settings = io.proximi.gadkit.GadSettings.STANDARD
        assertEquals(3.0, settings.orderSwitchMinimumMeters)
        assertFalse(VisitController.switchesStop(nearerMeters = 0.4, entersVisitorGallery = false, settings = settings))
        assertFalse(VisitController.switchesStop(nearerMeters = -2.0, entersVisitorGallery = false, settings = settings))
        assertTrue(VisitController.switchesStop(nearerMeters = 3.0, entersVisitorGallery = false, settings = settings))
        assertTrue(VisitController.switchesStop(nearerMeters = -2.0, entersVisitorGallery = true, settings = settings))
        assertTrue(VisitController.switchesStop(nearerMeters = 0.0, entersVisitorGallery = false, settings = settings.copy(orderSwitchMinimumMeters = 0.0)))
    }

    /** iOS 1.54.0: the nearest open artwork of the visitor's gallery becomes the stop (PM's case: 16.5 m and 3.7 m). */
    @Test
    fun theNearestArtworkOfTheGalleryBecomesTheStop() {
        val settings = io.proximi.gadkit.GadSettings.STANDARD
        assertTrue(settings.ordersNearestArtworkInGalleryFirst)
        val nearest = VisitController.nearestStop(listOf("far" to 16.5, "near" to 3.7, "other" to 9.0), active = "far", settings = settings)
        assertEquals("near", nearest?.first)
        assertEquals(12.8, nearest!!.second, 0.001)
        assertEquals(null, VisitController.nearestStop(listOf("far" to 16.5, "near" to 3.7), active = "near", settings = settings))
        assertEquals(null, VisitController.nearestStop(listOf("far" to 6.0, "near" to 3.7), active = "far", settings = settings))
        assertEquals(null, VisitController.nearestStop(listOf("near" to 3.7), active = "far", settings = settings))
        assertEquals(null, VisitController.nearestStop(listOf("a" to 5.0, "b" to 5.0), active = "b", settings = settings.copy(orderSwitchMinimumMeters = 0.0)))
    }

    /** iOS 1.55.0 and 1.55.1: the arrival near the geofence is off by default; a host app can set a distance. */
    @Test
    fun theArrivalNearTheGeofenceIsOffByDefault() {
        val settings = io.proximi.gadkit.GadSettings.STANDARD
        assertEquals(0.0, settings.artworkArrivalMarginMeters)
        assertFalse(VisitController.arrivesNearGeofence(1.0, 60_000, settings))
        val on = settings.copy(artworkArrivalMarginMeters = 2.5)
        assertTrue(on.artworkArrivalMarginMeters < on.artworkVisitedMeters)
        assertFalse(VisitController.arrivesNearGeofence(2.0, 2_900, on))
        assertTrue(VisitController.arrivesNearGeofence(2.0, 3_000, on))
        assertFalse(VisitController.arrivesNearGeofence(2.6, 60_000, on))
    }
}
