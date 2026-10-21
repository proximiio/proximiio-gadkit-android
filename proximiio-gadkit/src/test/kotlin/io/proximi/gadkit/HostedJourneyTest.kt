package io.proximi.gadkit

import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.StopKind
import io.proximi.gadkit.visit.StopState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `HostedJourneyTests`. */
class HostedJourneyTest {
    private val places = listOf(
        VenuePlace(id = "a", title = "Artwork A", coordinate = Coordinate(24.5, 54.4), level = 1.0, remoteId = "GAD.1"),
        VenuePlace(id = "b", title = "Artwork B", coordinate = Coordinate(24.6, 54.5), level = 1.0, remoteId = "GAD.2"),
        VenuePlace(id = "c", title = "Artwork C", coordinate = Coordinate(24.7, 54.6), level = 3.0),
    )

    private fun stop(id: String, state: ProximiJourney.State = ProximiJourney.State.PENDING) = ProximiJourney.Stop(id, state)

    private fun Journey.withStates(vararg states: StopState) = Journey(stops.mapIndexed { index, stop -> stop.copy(state = states[index]) })

    @Test
    fun stopsMatchByCollectionIdOrPoiIdAndKeepTheirStates() {
        val hosted = ProximiJourney(JourneyKind.CURATED, listOf(stop("GAD.2", ProximiJourney.State.VISITED), stop("c", ProximiJourney.State.SKIPPED), stop("GAD.1")))
        val matched = HostedJourney.of(hosted, places)
        assertEquals(listOf("b", "c", "a"), matched.journey.stops.map { it.id })
        assertEquals(listOf(StopState.DONE, StopState.SKIPPED, StopState.PENDING), matched.journey.stops.map { it.state })
        assertTrue(matched.unmatched.isEmpty())
        assertEquals(hosted, matched.hostJourney(matched.journey, JourneyKind.CURATED))
    }

    @Test
    fun unknownAndRepeatedStopsStayAfterTheOthers() {
        val hosted = ProximiJourney(stops = listOf(stop("GAD.404"), stop("a"), stop("GAD.1", ProximiJourney.State.VISITED), stop("b")))
        val matched = HostedJourney.of(hosted, places)
        assertEquals(listOf("a", "b"), matched.journey.stops.map { it.id })
        assertEquals(listOf("GAD.404", "GAD.1"), matched.unmatched.map { it.id })
        assertEquals(listOf("a", "b", "GAD.404", "GAD.1"), matched.hostJourney(matched.journey, JourneyKind.PERSONALISED).stops.map { it.id })
    }

    @Test
    fun theWrittenJourneyFollowsTheNavigatorsOrderAndStates() {
        val matched = HostedJourney.of(ProximiJourney.ofArtworks(listOf("GAD.1", "GAD.2", "c")), places)
        val stops = matched.journey.stops
        val cafe = JourneyStop(
            id = "cafe", title = "Café", coordinate = Coordinate(24.5, 54.4), level = null, poiId = "cafe",
            state = StopState.ACTIVE, kind = StopKind.DETOUR,
        )
        val journey = Journey(
            listOf(stops[2].copy(state = StopState.DONE), stops[0].copy(state = StopState.REACHED), stops[1].copy(state = StopState.SKIPPED), cafe),
        )
        val written = matched.hostJourney(journey, JourneyKind.PERSONALISED)
        assertEquals(listOf("c", "GAD.1", "GAD.2"), written.stops.map { it.id })
        // A reached stop is pending until it is done; the detour is not a stop of the host app.
        assertEquals(listOf(ProximiJourney.State.VISITED, ProximiJourney.State.PENDING, ProximiJourney.State.SKIPPED), written.stops.map { it.state })
    }

    /** A removed stop, a new kind and a first journey on a map without one start the screen again. */
    @Test
    fun theOpenScreenTakesAJourneyInPlaceUnlessItCannot() {
        val shown = HostedJourney.of(ProximiJourney.ofArtworks(listOf("GAD.1", "GAD.2")), places)
        val more = HostedJourney.of(ProximiJourney(stops = listOf(stop("GAD.2", ProximiJourney.State.VISITED), stop("c"), stop("GAD.1"))), places)
        assertTrue(more.canReplace(shown, JourneyKind.PERSONALISED, JourneyKind.PERSONALISED))
        assertFalse(more.canReplace(shown, JourneyKind.CURATED, JourneyKind.PERSONALISED))
        val fewer = HostedJourney.of(ProximiJourney.ofArtworks(listOf("GAD.1")), places)
        assertFalse(fewer.canReplace(shown, JourneyKind.PERSONALISED, JourneyKind.PERSONALISED))
        val empty = HostedJourney.of(ProximiJourney.ofArtworks(emptyList()), places)
        assertFalse(more.canReplace(empty, JourneyKind.PERSONALISED, JourneyKind.PERSONALISED))
    }

    @Test
    fun stateChangesTakeTheHostAppsStates() {
        val base = HostedJourney.of(ProximiJourney.ofArtworks(listOf("GAD.1", "GAD.2", "c")), places).journey
        val current = base.withStates(StopState.ACTIVE, StopState.DONE, StopState.SKIPPED)
        val target = HostedJourney.of(
            ProximiJourney(stops = listOf(stop("GAD.1", ProximiJourney.State.VISITED), stop("GAD.2"), stop("c", ProximiJourney.State.SKIPPED))),
            places,
        ).journey
        // Reopens come first.
        assertEquals(
            listOf(
                JourneyUpdate.StateChange("b", JourneyUpdate.StateChange.Kind.REOPEN),
                JourneyUpdate.StateChange("a", JourneyUpdate.StateChange.Kind.MARK_DONE),
            ),
            JourneyUpdate.stateChanges(current, target),
        )
        // Active and reached are pending for the host app: nothing to change.
        val reached = base.withStates(StopState.ACTIVE, StopState.REACHED, StopState.SKIPPED)
        val pending = HostedJourney.of(ProximiJourney.ofArtworks(listOf("GAD.1", "GAD.2")), places).journey
        assertTrue(JourneyUpdate.stateChanges(reached, pending).isEmpty())
    }

    /** The navigator does not skip a done stop: a visited stop the host app skips is reopened first. */
    @Test
    fun aVisitedStopTheHostAppSkipsIsReopenedFirst() {
        val current = HostedJourney.of(ProximiJourney.ofArtworks(listOf("GAD.1", "GAD.2")), places).journey.withStates(StopState.DONE, StopState.SKIPPED)
        val target = HostedJourney.of(
            ProximiJourney(stops = listOf(stop("GAD.1", ProximiJourney.State.SKIPPED), stop("GAD.2", ProximiJourney.State.VISITED))),
            places,
        ).journey
        assertEquals(
            listOf(
                JourneyUpdate.StateChange("a", JourneyUpdate.StateChange.Kind.REOPEN),
                JourneyUpdate.StateChange("a", JourneyUpdate.StateChange.Kind.SKIP),
                JourneyUpdate.StateChange("b", JourneyUpdate.StateChange.Kind.MARK_DONE),
            ),
            JourneyUpdate.stateChanges(current, target),
        )
    }

    @Test
    fun theOrderPutsTheHostAppsPendingStopsInItsOrder() {
        assertNull(JourneyUpdate.order(listOf("a", "b", "c"), "a", listOf("a", "b", "c"), emptySet()))
        assertEquals(listOf("c", "a", "b"), JourneyUpdate.order(listOf("a", "b", "c"), "a", listOf("c", "a", "b"), emptySet()))
        // The active stop the host app closes stays first, so the route changes once, to C.
        assertEquals(listOf("a", "c", "b"), JourneyUpdate.order(listOf("a", "b", "c"), "a", listOf("c", "b"), setOf("a")))
        // A stop the host app closes that is not active goes after the pending ones.
        assertEquals(listOf("a", "c", "b"), JourneyUpdate.order(listOf("a", "b", "c"), "a", listOf("a", "c"), setOf("b")))
        // An id in one list only: the rest keep their order.
        assertEquals(listOf("b", "a", "x"), JourneyUpdate.order(listOf("a", "x", "b"), "a", listOf("b", "a", "y"), emptySet()))
    }

    @Test
    fun aJourneyCanBeStoredAsJson() {
        val journey = ProximiJourney(JourneyKind.CURATED, listOf(stop("GAD.1", ProximiJourney.State.VISITED), stop("b")))
        assertEquals(journey, ProximiJourney.fromJson(journey.toJson()))
        assertEquals("""{"kind":"curated","stops":[{"id":"GAD.1","state":"visited"},{"id":"b","state":"pending"}]}""", journey.toJson())
        assertNull(ProximiJourney.fromJson("not json"))
    }

    @Test
    fun theDebugLineCountsTheStates() {
        val journey = ProximiJourney(stops = listOf(stop("a"), stop("b", ProximiJourney.State.SKIPPED), stop("c")))
        assertEquals("pending 2 · visited 0 · skipped 1", hostedStates(journey))
    }
}
