package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.VenueGeofence
import io.proximi.gadkit.map.VenuePlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of guggenheim-ios `VisitPlanTests`. `visitStoreRoundTripsAndClears` tests persistence,
 * which lives in the app: `app/src/test/.../visit/VisitStoreCodecTest`.
 */
class VisitPlanTest {
    private val places = listOf(
        VenuePlace(id = "a", title = "Gallery 101", coordinate = Coordinate(24.5, 54.4), level = 1.0),
        VenuePlace(id = "b", title = "Artwork 1", coordinate = Coordinate(24.6, 54.5), level = 1.0),
        VenuePlace(id = "c", title = "Gallery 304", coordinate = Coordinate(24.7, 54.6), level = 3.0),
    )

    @Test
    fun journeyKeepsTheGivenOrderAndDropsUnknownIDs() {
        val journey = VisitPlan.journey(listOf("c", "x", "a"), places)
        assertEquals(listOf("c", "a"), journey.stops.map { it.id })
        assertEquals(3.0, journey.stops[0].level)
        assertTrue(journey.stops.all { it.state == StopState.PENDING && it.poiId == it.id })
    }

    @Test
    fun eachStopIsGroupedByTheGalleryThatContainsIt() {
        val outline = listOf(Coordinate(24.49, 54.39), Coordinate(24.49, 54.41), Coordinate(24.51, 54.41), Coordinate(24.51, 54.39))
        val gallery = Gallery(id = "g101", name = "Gallery 101", level = 1.0, ring = outline)
        val journey = VisitPlan.journey(listOf("a", "c"), places, listOf(gallery))
        assertEquals(listOf("g101", null), journey.stops.map { it.group })
        assertNull(VisitPlan.journey(listOf("a"), places).stops[0].group)
    }

    @Test
    fun skippedStopsAreNotNumberedAndTheRestRenumber() {
        var journey = VisitPlan.journey(listOf("a", "b", "c"), places)
        journey = VisitPlan.setting(StopState.SKIPPED, "b", journey)
        assertEquals(mapOf("a" to 1, "c" to 2), VisitPlan.numbers(journey))
        journey = VisitPlan.setting(StopState.PENDING, "b", journey)
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3), VisitPlan.numbers(journey))
    }

    @Test
    fun detoursAreNotNumberedListedOrCounted() {
        val journey = VisitPlan.journey(listOf("a", "c"), places)
        val detour = VisitPlan.stop(places[1]).copy(kind = StopKind.DETOUR, state = StopState.ACTIVE)
        val withDetour = Journey(journey.stops.toMutableList().apply { add(1, detour) })
        assertEquals(mapOf("a" to 1, "c" to 2), VisitPlan.numbers(withDetour))
        assertEquals(listOf("a", "c"), VisitPlan.artworks(withDetour).map { it.id })
        assertEquals(2, VisitPlan.remainingStopCount(withDetour))
        assertFalse(VisitPlan.isLastOpenStop(withDetour.stops[2], withDetour))
    }

    @Test
    fun artworkAreaContainsPointsInsideTheCircleOrPolygon() {
        val center = Coordinate(24.5, 54.4)
        val circle = ArtworkArea(center, radius = 3.0)
        // 0.00002° of latitude is about 2.2 m; 0.00004° about 4.4 m.
        assertTrue(circle.contains(Coordinate(24.50002, 54.4)))
        assertFalse(circle.contains(Coordinate(24.50004, 54.4)))
        assertFalse(ArtworkArea(Coordinate(0.0, 0.0), radius = 3.0).contains(Coordinate(0.0, 0.0)))
        val square = ArtworkArea(null, radius = 0.0, polygon = listOf(listOf(54.4, 24.5), listOf(54.401, 24.5), listOf(54.401, 24.501), listOf(54.4, 24.501)))
        assertTrue(square.contains(Coordinate(24.5005, 54.4005)))
        assertFalse(square.contains(Coordinate(24.502, 54.4005)))
    }

    /**
     * ProximiioGADKit 0.13.0, GAD data 2026-10-15: "DCT Mother/Earth" shares `remote_id` with the
     * Guggenheim artwork. Only the first geofence was kept, so the visitor inside the DCT one did not
     * reach it.
     */
    @Test
    fun everyGeofenceOfAnArtworkIsKept() {
        val guggenheim = Coordinate(24.5, 54.4)
        val dct = Coordinate(25.2, 55.3)
        fun geofence(id: String, center: Coordinate, remoteId: String, type: String = "artwork") =
            VenueGeofence(id, name = null, polygon = null, floorId = null, remoteId = remoteId, geofenceType = type, center = center, radius = 2.0)
        val areas = VisitController.artworkAreas(
            listOf(
                geofence("g", guggenheim, "GAD.2018.00034"),
                geofence("d", dct, "GAD.2018.00034"),
                geofence("n", dct, ""),
                geofence("x", dct, "e07fe5d7", type = "gallery"),
            ),
        )
        assertEquals(listOf("GAD.2018.00034"), areas.keys.sorted())
        assertTrue(areas.getValue("GAD.2018.00034").any { it.contains(dct) })
        assertTrue(areas.getValue("GAD.2018.00034").any { it.contains(guggenheim) })
    }

    @Test
    fun geofenceDwellCountsUnbrokenTimeInside() {
        val dwell = GeofenceDwell()
        assertEquals(emptyList(), dwell.update(setOf("b"), nowMillis = 0, dwellMillis = 3_000))
        assertEquals(emptyList(), dwell.update(setOf("b", "c"), nowMillis = 2_000, dwellMillis = 3_000))
        assertEquals(listOf("b"), dwell.update(setOf("b", "c"), nowMillis = 3_000, dwellMillis = 3_000))
        // Leaving resets the time.
        assertEquals(listOf("b"), dwell.update(setOf("b"), nowMillis = 4_000, dwellMillis = 3_000))
        assertEquals(emptyList(), dwell.update(setOf("c"), nowMillis = 5_000, dwellMillis = 3_000))
        assertEquals(listOf("c"), dwell.update(setOf("c"), nowMillis = 8_000, dwellMillis = 3_000))
    }

    @Test
    fun onlyTheActiveArtworkGeofenceCounts() {
        var journey = VisitPlan.journey(listOf("a", "b", "c"), places)
        val remoteIds = mapOf("a" to "GAD.1", "b" to "GAD.2", "c" to "GAD.3")
        assertNull(ArtworkArrival.activeStopId("GAD.1", journey, remoteIds))
        journey = VisitPlan.setting(StopState.ACTIVE, "a", journey)
        assertEquals("a", ArtworkArrival.activeStopId("GAD.1", journey, remoteIds))
        assertNull(ArtworkArrival.activeStopId("GAD.2", journey, remoteIds))
        assertNull(ArtworkArrival.activeStopId(null, journey, remoteIds))
        journey = VisitPlan.setting(StopState.REACHED, "a", journey)
        assertEquals("a", ArtworkArrival.activeStopId("GAD.1", journey, remoteIds))
    }

    @Test
    fun cardStates() {
        val stops = VisitPlan.journey(listOf("a", "c"), places).stops
        assertEquals(VisitCardState.Walking("Gallery 101"), VisitCardState.make(stops[0], hasArrived = false, isFinished = false, visitorLevel = 1.0))
        assertEquals(VisitCardState.NextStopOnOtherLevel("Gallery 304", 3), VisitCardState.make(stops[1], hasArrived = false, isFinished = false, visitorLevel = 1.0))
        assertEquals(VisitCardState.Walking("Gallery 304"), VisitCardState.make(stops[1], hasArrived = true, isFinished = false, visitorLevel = 3.0))
        assertEquals(
            VisitCardState.AtStop("Gallery 304"),
            VisitCardState.make(stops[1], hasArrived = true, isFinished = false, visitorLevel = 3.0, showsArrival = true),
        )
        assertEquals(VisitCardState.Walking("Gallery 304"), VisitCardState.make(stops[1], hasArrived = false, isFinished = false, visitorLevel = null))
        assertEquals(VisitCardState.Finished, VisitCardState.make(null, hasArrived = false, isFinished = true, visitorLevel = 1.0))
        assertEquals(VisitCardState.Starting, VisitCardState.make(null, hasArrived = false, isFinished = false, visitorLevel = 1.0))
    }
}

/** Port of guggenheim-ios `ArrivalTests` (in VisitPlanTests.swift). */
class ArrivalTest {
    @Test
    fun venueCentreIsTheMeanOfThePlaces() {
        val places = listOf(
            VenuePlace(id = "a", title = "A", coordinate = Coordinate(24.0, 54.0), level = 1.0),
            VenuePlace(id = "b", title = "B", coordinate = Coordinate(25.0, 55.0), level = 1.0),
        )
        val centre = VenuePlace.centre(places)
        assertEquals(24.5, centre?.latitude)
        assertEquals(54.5, centre?.longitude)
        assertNull(VenuePlace.centre(emptyList()))
    }
}

/** Port of guggenheim-ios `RemainingStopsTests` (in VisitPlanTests.swift). */
class RemainingStopsTest {
    @Test
    fun finishedJourneyHasNoRemainingStops() {
        val coordinate = Coordinate(0.0, 0.0)
        val places = listOf(
            VenuePlace(id = "a", title = "A", coordinate = coordinate, level = 1.0),
            VenuePlace(id = "b", title = "B", coordinate = coordinate, level = 1.0),
        )
        var journey = VisitPlan.journey(listOf("a", "b"), places)
        assertEquals(2, VisitPlan.remainingStopCount(journey))
        journey = VisitPlan.setting(StopState.DONE, "a", journey)
        journey = VisitPlan.setting(StopState.SKIPPED, "b", journey)
        assertEquals(0, VisitPlan.remainingStopCount(journey))
    }
}
