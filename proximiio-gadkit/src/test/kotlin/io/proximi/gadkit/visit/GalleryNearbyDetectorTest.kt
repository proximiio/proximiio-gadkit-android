package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
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
}
