package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.GadSettings
import io.proximi.gadkit.ProximiGallery
import io.proximi.gadkit.ProximiMap
import io.proximi.gadkit.ProximiMapEvent
import io.proximi.gadkit.ProximiStop
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GalleryDestination
import io.proximi.gadkit.map.VenuePlace
import io.proximi.map.core.FloorKey
import io.proximi.map.core.MapCoordinate
import io.proximi.map.core.VenuePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.proximi.map.core.JourneyStop as MapJourneyStop

/** Port of ProximiioGADKit `ProximiMapTests` for the gallery of a stop and `galleryNearby` (iOS 1.37.0). */
class GalleryNearbyDetectorTest {
    /** An artwork in gallery G403, the gallery's POI, and an artwork without a gallery. */
    private val places = listOf(
        VenuePlace(id = "poi-a", title = "Cloud Canyons", coordinate = Coordinate(24.5, 54.4), level = 3.0, remoteId = "GAD.2010.00033", isArtwork = true, galleryId = "G403"),
        VenuePlace(id = "poi-b", title = "Tongari-kun", coordinate = Coordinate(24.5001, 54.4001), level = 3.0, remoteId = "GAD.2018.00034", isArtwork = true),
        VenuePlace(id = "poi-g403", title = "G403 Weaving", coordinate = Coordinate(24.5003, 54.4003), level = 3.0, remoteId = "G403"),
    )
    private val poi = places[2].coordinate

    private fun stop(id: String) = JourneyStop(id = id, title = id, coordinate = Coordinate(24.5, 54.4), level = 1.0, poiId = id)

    private fun mapStop(id: String) = MapJourneyStop(id = id, title = id, coordinate = MapCoordinate(24.5, 54.4), poiId = id)

    private fun position(metresNorth: Double, level: Double) =
        VenuePosition(MapCoordinate(poi.latitude + metresNorth / 111_320, poi.longitude), horizontalAccuracy = 1.0, timestampMillis = 0, floor = FloorKey(level))

    @Test
    fun aStopCarriesItsGalleryAndGalleryNearbyNamesIt() {
        val gallery = ProximiGallery(id = "poi-g403", galleryId = "G403", title = "G403 Weaving", level = 3, latitude = 24.5003, longitude = 54.4003)
        val inGallery = ProximiStop(id = "poi-a", title = "poi-a", artworkId = "GAD.2010.00033", level = 1, gallery = gallery)
        assertEquals(ProximiMapEvent.StopNearby(inGallery), ProximiMap.event(VisitEvent.StopNearby(stop("poi-a")), places))
        assertEquals(ProximiMapEvent.GalleryNearby(gallery, inGallery), ProximiMap.event(VisitEvent.GalleryNearby(stop("poi-a")), places))
        // An artwork without a gallery has none, and reports no gallery nearby.
        assertEquals(
            ProximiMapEvent.StopNearby(ProximiStop(id = "poi-b", title = "poi-b", artworkId = "GAD.2018.00034", level = 1)),
            ProximiMap.event(VisitEvent.StopNearby(stop("poi-b")), places),
        )
        assertNull(ProximiMap.event(VisitEvent.GalleryNearby(stop("poi-b")), places))
        // An artwork is never taken for a gallery, whatever its `remote_id`.
        assertNull(GalleryNearbyDetector.gallery("poi-g403", places))
        assertEquals("poi-g403", GalleryNearbyDetector.gallery("poi-a", places)?.id)
    }

    @Test
    fun aGalleryIsReportedOnceUntilAnotherGalleryIsAheadOrTheJourneyResumes() {
        val detector = GalleryNearbyDetector(nearbyMeters = 5.0)
        val near = position(metresNorth = 3.0, level = 3.0)
        val far = position(metresNorth = 30.0, level = 3.0)
        val below = position(metresNorth = 3.0, level = 1.0)
        fun update(position: VenuePosition?, id: String) = detector.update(position, mapStop(id), places, outlines = emptyList())?.id
        assertNull(update(far, "poi-a"))
        assertNull(update(below, "poi-a"))
        assertNull(update(null, "poi-a"))
        assertEquals("poi-a", update(near, "poi-a"))
        assertNull(update(near, "poi-a"))
        // A stop without a gallery in between: the gallery is ahead again afterwards.
        assertNull(update(near, "poi-b"))
        assertEquals("poi-a", update(near, "poi-a"))
        // The journey resumed after a pause.
        detector.reset()
        assertEquals("poi-a", update(near, "poi-a"))
    }

    @Test
    fun aVisitorInsideTheGalleryIsNearbyAtAnyDistance() {
        val detector = GalleryNearbyDetector(nearbyMeters = 5.0)
        // An outline of about 110 × 100 m around the POI.
        val ring = listOf(Coordinate(24.4998, 54.4), Coordinate(24.4998, 54.401), Coordinate(24.5008, 54.401), Coordinate(24.5008, 54.4))
        val outline = GalleryDestination(Gallery("G403", "G403 Weaving", 3.0, ring), entry = poi, placeId = "poi-g403")
        val inside = position(metresNorth = 30.0, level = 3.0)
        assertEquals("poi-a", detector.update(inside, mapStop("poi-a"), places, listOf(outline))?.id)
    }

    /** iOS 1.42.0. */
    @Test
    fun aGalleryWithEntrancesIsNearbyAtAnEntranceNotAtItsPOI() {
        val amenity = GadSettings.STANDARD.galleryEntranceAmenityId
        // Two entrances of G403, 40 m north and 40 m south of the gallery's POI, and one of another gallery at the POI.
        fun entrance(id: String, metresNorth: Double, gallery: String) = VenuePlace(
            id = id, title = "Entrance", coordinate = Coordinate(poi.latitude + metresNorth / 111_320, poi.longitude), level = 3.0,
            amenityId = amenity, galleryId = gallery,
        )
        val withEntrances = places + listOf(entrance("e1", 40.0, "G403"), entrance("e2", -40.0, "G403"), entrance("e3", 0.0, "G101"))
        assertEquals(listOf("e1", "e2"), GalleryNearbyDetector.entrances("G403", withEntrances, amenity).map { it.id })
        assertTrue(GalleryNearbyDetector.entrances("G403", places, amenity).isEmpty())
        // The maps and the search leave the entrances out and hide their features (iOS 1.43.0).
        assertEquals(places, VenuePlace.withoutGalleryEntrances(withEntrances, amenity))
        assertEquals(setOf("e1", "e2", "e3"), VenuePlace.galleryEntranceIds(withEntrances, amenity))
        assertTrue(VenuePlace.matching("entrance", VenuePlace.withoutGalleryEntrances(withEntrances, amenity)).isEmpty())
        val detector = GalleryNearbyDetector(nearbyMeters = 5.0)
        // At the gallery's POI: not nearby, the gallery has entrances.
        assertNull(detector.update(position(2.0, 3.0), mapStop("poi-a"), withEntrances, emptyList()))
        assertEquals("poi-a", detector.update(position(-37.0, 3.0), mapStop("poi-a"), withEntrances, emptyList())?.id)
        detector.reset()
        assertEquals("poi-a", detector.update(position(43.0, 3.0), mapStop("poi-a"), withEntrances, emptyList())?.id)
    }

    /** iOS 1.42.0. */
    @Test
    fun anotherGalleryOfTheJourneyIsReportedWhenTheVisitorEntersIt() {
        // G403 around its POI, 20 m wide. poi-a is an artwork in G403; the active stop poi-b has no gallery.
        val d = 10 / 111_320.0
        val ring = listOf(-d to -d, -d to d, d to d, d to -d).map { (lat, lng) -> Coordinate(poi.latitude + lat, poi.longitude + lng) }
        val outline = GalleryDestination(Gallery("G403", "G403 Weaving", 3.0, ring), entry = poi, placeId = "poi-g403")
        val detector = GalleryNearbyDetector(nearbyMeters = 5.0)
        val open = listOf(mapStop("poi-b"), mapStop("poi-a"))
        fun update(metresNorth: Double, active: String = "poi-b") =
            detector.update(position(metresNorth, 3.0), mapStop(active), places, listOf(outline), open)?.id
        assertNull(update(60.0))
        // Inside G403 while the route leads to poi-b: reported once, with the artwork of G403.
        assertEquals("poi-a", update(2.0))
        assertNull(update(3.0))
        // Out and in again: reported again.
        assertNull(update(60.0))
        assertEquals("poi-a", update(2.0))
        // The artwork of G403 becomes the active stop while the visitor is still inside: not reported twice.
        assertNull(update(2.0, active = "poi-a"))
        assertNull(update(3.0, active = "poi-a"))
    }

    /**
     * Another gallery of the journey is reported after the visitor has been inside it for the entry
     * time; a visitor who steps out earlier gets no report (Metis T-1928; iOS 1.71.0).
     */
    @Test
    fun anotherGalleryIsReportedAfterTheEntryTime() {
        val d = 10 / 111_320.0
        val ring = listOf(-d to -d, -d to d, d to d, d to -d).map { (lat, lng) -> Coordinate(poi.latitude + lat, poi.longitude + lng) }
        val outline = GalleryDestination(Gallery("G403", "G403 Weaving", 3.0, ring), entry = poi, placeId = "poi-g403")
        val detector = GalleryNearbyDetector(nearbyMeters = 5.0, otherGalleryMillis = 3_000)
        val open = listOf(mapStop("poi-b"), mapStop("poi-a"))
        fun update(metresNorth: Double, atMillis: Long) =
            detector.update(position(metresNorth, 3.0), mapStop("poi-b"), places, listOf(outline), open, nowMillis = 1_000_000 + atMillis)?.id
        // At the edge for 2 s, then out: no report.
        assertNull(update(9.0, 0))
        assertNull(update(9.0, 2_000))
        assertNull(update(60.0, 4_000))
        // In again: the time starts again; reported once after 3 s inside.
        assertNull(update(2.0, 10_000))
        assertNull(update(2.0, 12_900))
        assertEquals("poi-a", update(2.0, 13_000))
        assertNull(update(2.0, 14_000))
        // The settings: no own value takes the entry time of the order.
        assertNull(GadSettings.STANDARD.otherGalleryNearbySeconds)
    }
}
