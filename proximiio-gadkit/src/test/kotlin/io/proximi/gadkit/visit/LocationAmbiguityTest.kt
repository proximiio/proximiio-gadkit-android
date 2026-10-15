package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.VenuePlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of guggenheim-ios `LocationAmbiguityTests`. */
class LocationAmbiguityTest {
    private val here = Coordinate(24.5, 54.4)
    private val artworks = listOf(place("a", "Artwork A", 2.0), place("b", "Artwork B", 4.0), place("c", "Artwork C", 30.0))

    private fun input(
        accuracy: Double = 3.0,
        stops: List<String> = listOf("a", "b", "c"),
        active: String? = null,
        galleries: List<VenuePlace> = emptyList(),
        currentGallery: String? = null,
    ): LocationAmbiguity.Input {
        val journey = VisitPlan.journey(stops, artworks)
        return LocationAmbiguity.Input(
            coordinate = here,
            level = 1.0,
            horizontalAccuracy = accuracy,
            activeStop = active?.let { id -> journey.stops.firstOrNull { it.id == id } },
            galleries = galleries,
            currentGallery = currentGallery,
        )
    }

    @Test
    fun nearbyStopsAreNotAmbiguous() {
        assertNull(LocationAmbiguity.evaluate(input()))
        assertNull(LocationAmbiguity.evaluate(input(stops = listOf("a", "c"))))
    }

    @Test
    fun betweenGalleriesOnlyOutsideAGalleryGeofence() {
        val galleries = listOf(place("g1", "Gallery 101", 8.0), place("g2", "Gallery 102", -8.0), place("g3", "Gallery 103", 80.0))
        val outside = input(stops = listOf("c"), galleries = galleries)
        assertEquals("galleries", LocationAmbiguity.evaluate(outside)?.kind)
        assertNull(LocationAmbiguity.evaluate(input(stops = listOf("c"), galleries = galleries, currentGallery = "Gallery 101")))
    }

    @Test
    fun uncertainPositionNearTheActiveStopIsASoftPrompt() {
        assertNull(LocationAmbiguity.evaluate(input(accuracy = 12.0, stops = listOf("c"), active = "c")))
        assertEquals("low", LocationAmbiguity.evaluate(input(accuracy = 40.0, stops = listOf("c"), active = "c"))?.kind)
        assertNull(LocationAmbiguity.evaluate(input(accuracy = 5.0, stops = listOf("c"), active = "c")))
    }

    @Test
    fun galleriesAreFoundByTitle() {
        assertEquals(listOf("g"), LocationAmbiguity.galleries(artworks + place("g", "Gallery 101", 0.0)).map { it.id })
    }

    private companion object {
        /** 0.00001° of latitude is about 1.1 m. */
        fun place(id: String, title: String, north: Double, level: Double = 1.0) =
            VenuePlace(id = id, title = title, coordinate = Coordinate(24.5 + north / 111_000, 54.4), level = level)
    }
}

/** Port of guggenheim-ios `JourneyLifecycleTests` (in LocationAmbiguityTests.swift). */
class JourneyLifecycleTest {
    private val places = listOf("a", "b", "c", "d").map {
        VenuePlace(id = it, title = it.uppercase(), coordinate = Coordinate(24.5, 54.4), level = 1.0)
    }

    @Test
    fun lastOpenStop() {
        var journey = VisitPlan.journey(listOf("a", "b"), places)
        assertFalse(VisitPlan.isLastOpenStop(journey.stops[1], journey))
        journey = VisitPlan.setting(StopState.DONE, "a", journey)
        assertTrue(VisitPlan.isLastOpenStop(journey.stops[1], journey))
    }

    /** iOS round-trips through `UserDefaults`; persistence is the app's, so core checks value equality only. */
    @Test
    fun visitMetaRoundTripsAndClears() {
        val meta = VisitMeta(title = "Light & Perception", summary = "8 stops")
        assertEquals(meta, meta.copy())
        assertEquals(VisitMeta("Light & Perception", "8 stops"), meta)
    }
}

/** Not in iOS: the constants and the stop filter of `VisitController`. */
class JourneyConstantsTest {
    @Test
    fun arrivalRadiusIsTwoAndAHalfTimesTheAccuracyWithinFiveToTenMetres() {
        assertEquals(5.0, JourneyConstants.arrivalRadiusMeters(1.0))
        assertEquals(7.5, JourneyConstants.arrivalRadiusMeters(3.0))
        assertEquals(10.0, JourneyConstants.arrivalRadiusMeters(8.0))
        assertEquals(5.0, JourneyConstants.ARRIVAL_DWELL_SECONDS)
        assertEquals(5.0, JourneyConstants.AUTO_ADVANCE_ON_DEPARTURE_METERS)
    }
}
