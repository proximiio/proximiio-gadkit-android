package io.proximi.gadkit

import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.StopKind
import io.proximi.gadkit.visit.StopState
import io.proximi.map.core.MapCoordinate
import kotlin.test.Test
import io.proximi.map.core.Journey as MapJourney
import io.proximi.map.core.JourneyStop as MapJourneyStop
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

    /**
     * A new kind, a journey without a stop on the map and a first journey on a map without one start
     * the screen again. Fewer stops are taken in place (`JourneyNavigator.update(to)`).
     */
    @Test
    fun theOpenScreenTakesAJourneyInPlaceUnlessItCannot() {
        val shown = HostedJourney.of(ProximiJourney.ofArtworks(listOf("GAD.1", "GAD.2")), places)
        val more = HostedJourney.of(ProximiJourney(stops = listOf(stop("GAD.2", ProximiJourney.State.VISITED), stop("c"), stop("GAD.1"))), places)
        assertTrue(more.canReplace(shown, JourneyKind.PERSONALISED, JourneyKind.PERSONALISED))
        assertFalse(more.canReplace(shown, JourneyKind.CURATED, JourneyKind.PERSONALISED))
        val fewer = HostedJourney.of(ProximiJourney.ofArtworks(listOf("GAD.1")), places)
        assertTrue(fewer.canReplace(shown, JourneyKind.PERSONALISED, JourneyKind.PERSONALISED))
        val empty = HostedJourney.of(ProximiJourney.ofArtworks(emptyList()), places)
        assertFalse(more.canReplace(empty, JourneyKind.PERSONALISED, JourneyKind.PERSONALISED))
        assertFalse(empty.canReplace(shown, JourneyKind.PERSONALISED, JourneyKind.PERSONALISED))
        val offTheMap = HostedJourney.of(ProximiJourney.ofArtworks(listOf("GAD.unknown")), places)
        assertFalse(offTheMap.canReplace(shown, JourneyKind.PERSONALISED, JourneyKind.PERSONALISED))
    }

    @Test
    fun aJourneyCanBeStoredAsJson() {
        val journey = ProximiJourney(JourneyKind.CURATED, listOf(stop("GAD.1", ProximiJourney.State.VISITED), stop("b")))
        assertEquals(journey, ProximiJourney.fromJson(journey.toJson()))
        assertEquals("""{"kind":"curated","stops":[{"id":"GAD.1","state":"visited"},{"id":"b","state":"pending"}]}""", journey.toJson())
        assertNull(ProximiJourney.fromJson("not json"))
    }

    @Test
    fun aMapLibraryJourneyKeepsItsStopIdsAndProgressWithoutTheDetour() {
        fun stop(id: String, state: MapJourneyStop.State, kind: MapJourneyStop.Kind = MapJourneyStop.Kind.PLANNED) =
            MapJourneyStop(id = id, title = id, coordinate = MapCoordinate(24.5, 54.4), poiId = id, kind = kind, state = state)
        val journey = MapJourney(
            listOf(
                stop("a", MapJourneyStop.State.DONE), stop("b", MapJourneyStop.State.SKIPPED),
                stop("cafe", MapJourneyStop.State.ACTIVE, kind = MapJourneyStop.Kind.DETOUR),
                stop("c", MapJourneyStop.State.REACHED), stop("d", MapJourneyStop.State.ACTIVE), stop("e", MapJourneyStop.State.PENDING),
            ),
        )
        assertEquals(
            ProximiJourney(
                JourneyKind.CURATED,
                listOf(stop("a", ProximiJourney.State.VISITED), stop("b", ProximiJourney.State.SKIPPED), stop("c"), stop("d"), stop("e")),
            ),
            ProximiJourney.ofJourney(journey, JourneyKind.CURATED),
        )
    }

    @Test
    fun theDebugLineCountsTheStates() {
        val journey = ProximiJourney(stops = listOf(stop("a"), stop("b", ProximiJourney.State.SKIPPED), stop("c")))
        assertEquals("pending 2 · visited 0 · skipped 1", hostedStates(journey))
    }

    /** Nagarro, 2026-10-26: `StopOrderChanged` only for a new order of the stops, not for new states. */
    @Test
    fun onlyANewOrderOfTheStopsIsAReorder() {
        val shown = ProximiJourney(stops = listOf(stop("GAD.1"), stop("GAD.2"), stop("c")))
        val visited = shown.copy(stops = listOf(stop("GAD.1", ProximiJourney.State.VISITED), stop("GAD.2"), stop("c")))
        assertFalse(HostedMapModel.isReordered(shown, visited))
        val reordered = shown.copy(stops = listOf(stop("GAD.1"), stop("c"), stop("GAD.2")))
        assertTrue(HostedMapModel.isReordered(shown, reordered))
    }
}
