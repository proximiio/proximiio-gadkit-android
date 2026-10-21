package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.UiText
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GalleryDestination
import io.proximi.gadkit.map.VenueGeofence
import io.proximi.gadkit.map.VenuePlace
import io.proximi.sdk.wayfinding.ComputedRoute
import io.proximi.map.core.FloorKey
import io.proximi.map.core.MapCoordinate
import io.proximi.map.live.JourneyRouting
import io.proximi.sdk.wayfinding.NoRouteReason
import io.proximi.sdk.wayfinding.RouteOptions
import io.proximi.sdk.wayfinding.RouteResult
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `GalleryFirstTests` (card text). */
class GalleryFirstTest {
    private val square = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 1.0), Coordinate(1.0, 1.0), Coordinate(1.0, 0.0))
    private val g106 = Gallery("106", "Gallery 106", 1.0, square)

    @Test
    fun cardTextWithAGalleryAhead() {
        val walking = VisitCardState.Walking("Tongari-kun")
        assertEquals(UiText.Resource("visit_next_stop"), VisitCardText.caption(walking, isLastOpenStop = true, gallery = "Gallery 107", galleryAhead = "Gallery 106"))
        assertEquals(UiText.Verbatim("Gallery 106"), VisitCardText.title(walking, galleryAhead = "Gallery 106"))
        assertFalse(VisitCardText.showsStopNumber(walking, galleryAhead = "Gallery 106"))
        val otherLevel = VisitCardState.NextStopOnOtherLevel("Tongari-kun", 3)
        assertEquals(UiText.Resource("visit_next_stop_is", listOf("Gallery 106")), VisitCardText.caption(otherLevel, false, null, "Gallery 106"))
        assertEquals(UiText.Resource("visit_go_to_level", listOf(3)), VisitCardText.title(otherLevel, "Gallery 106"))
        assertFalse(VisitCardText.showsStopNumber(otherLevel, "Gallery 106"))
        assertEquals(UiText.Resource("visit_next_stop"), VisitCardText.caption(VisitCardState.Starting, false, null, "Gallery 106"))
        assertEquals(UiText.Resource("visit_your_journey"), VisitCardText.title(VisitCardState.Starting, "Gallery 106"))
    }

    @Test
    fun cardTextInsideTheStopsGallery() {
        val walking = VisitCardState.Walking("Tongari-kun")
        assertEquals(UiText.Resource("visit_gallery_x", listOf("Gallery 106")), VisitCardText.caption(walking, false, gallery = "Gallery 106", galleryAhead = null))
        assertEquals(UiText.Verbatim("Tongari-kun"), VisitCardText.title(walking, galleryAhead = null))
        assertTrue(VisitCardText.showsStopNumber(walking, galleryAhead = null))
        assertTrue(VisitCardText.showsStopNumber(VisitCardState.AtStop("Tongari-kun"), galleryAhead = "Gallery 106"))
    }
}

/** Port of ProximiioGADKit `GalleryFirstRoutingTests`, `GalleryDistanceTests`, `ArtworkAreaTests` and `GalleryDestinationTests`. */
class GalleryFirstRoutingTest {
    private val artwork = MapCoordinate(0.5, 0.5)
    private val outside = Coordinate(3.0, 3.0)
    private val inside = Coordinate(0.2, 0.2)
    private val destination = GalleryDestination(
        Gallery("e1d2cd21", "G303 Language", 3.0, listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 1.0), Coordinate(1.0, 1.0), Coordinate(1.0, 0.0))),
        entry = Coordinate(0.9, 0.9),
        placeId = "poi-g303",
    )

    private fun state(isEnabled: Boolean = true, reached: Boolean = false): GalleryFirstRouting.State {
        val key = GalleryFirstRouting.Key(artwork)
        return GalleryFirstRouting.State(isEnabled, mapOf(key to destination), if (reached) setOf(key) else emptySet())
    }

    @Test
    fun aLegFromOutsideTheGalleryEndsAtTheGallery() {
        assertEquals(destination, GalleryFirstRouting.gallery(outside, 3.0, artwork, state()))
        // Another floor is outside the gallery too.
        assertEquals(destination, GalleryFirstRouting.gallery(inside, 1.0, artwork, state()))
    }

    @Test
    fun aLegFromInsideTheGalleryEndsAtTheArtwork() {
        assertNull(GalleryFirstRouting.gallery(inside, 3.0, artwork, state()))
    }

    @Test
    fun aReachedOrUnknownArtworkOrADisabledRouterEndsAtTheArtwork() {
        assertNull(GalleryFirstRouting.gallery(outside, 3.0, artwork, state(reached = true)))
        assertNull(GalleryFirstRouting.gallery(outside, 3.0, artwork, state(isEnabled = false)))
        assertNull(GalleryFirstRouting.gallery(outside, 3.0, MapCoordinate(0.6, 0.6), state()))
    }

    @Test
    fun theLabelIsTheFirstWordOfTheName() {
        assertEquals("G303", destination.label)
    }

    /** Field test 2026-10-19: test copies of artworks named galleries in another building. */
    @Test
    fun aGalleryWithoutARouteIsLeftForTheArtwork() = runBlocking {
        val inner = RecordingRouting()
        val routing = GalleryFirstRouting(inner)
        routing.update(true, mapOf(GalleryFirstRouting.Key(artwork) to destination))
        val origin = MapCoordinate(outside.latitude, outside.longitude)
        routing.route(origin, FloorKey(3.0), artwork, FloorKey(3.0), RouteOptions())
        assertEquals(listOf(MapCoordinate(0.9, 0.9), artwork), inner.destinations)
        assertNull(routing.gallery(artwork))
        // Later legs go to the artwork at once.
        routing.route(origin, FloorKey(3.0), artwork, FloorKey(3.0), RouteOptions())
        assertEquals(3, inner.destinations.size)
    }

    @Test
    fun anArtworkLeadsToItsGalleryOnlyWhenItIsInIt() {
        fun artwork(latitude: Double, level: Double = 3.0) = VenuePlace(id = "a", title = "Tongari-kun", coordinate = Coordinate(latitude, 0.5), level = level)
        assertTrue(GalleryFirstRouting.isInGallery(artwork(0.5), destination, 5.0))
        // 0.00002° of latitude is about 2.2 m north of the outline.
        assertTrue(GalleryFirstRouting.isInGallery(artwork(1.00002), destination, 5.0))
        assertFalse(GalleryFirstRouting.isInGallery(artwork(1.0001), destination, 5.0))
        assertFalse(GalleryFirstRouting.isInGallery(artwork(0.5, level = 1.0), destination, 5.0))
    }

    @Test
    fun insideIsZeroAndOutsideIsTheDistanceToTheNearestEdge() {
        val square = Gallery("g", "G", 1.0, listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.001), Coordinate(0.001, 0.001), Coordinate(0.001, 0.0)))
        assertEquals(0.0, square.distanceTo(Coordinate(0.0005, 0.0005)))
        // 0.0001° of latitude is about 11.13 m.
        assertEquals(11.13, square.distanceTo(Coordinate(0.0011, 0.0005)), 0.1)
        // Beyond a corner: the distance to the corner.
        assertEquals(11.13 * sqrt(2.0), square.distanceTo(Coordinate(0.0011, 0.0011)), 0.2)
    }

    @Test
    fun artworkAreasKeepTheGeofenceId() {
        val geofence = VenueGeofence(
            "50c36aad", "DCT the broken 5", listOf(listOf(54.0, 24.0), listOf(54.1, 24.0), listOf(54.1, 24.1)), null,
            remoteId = "GAD.2020.00112", geofenceType = "artwork",
        )
        assertEquals("50c36aad", VisitController.artworkAreas(listOf(geofence)).getValue("GAD.2020.00112").single().id)
    }

    private fun galleryGeofence(remoteId: String?, polygon: List<List<Double>>? = listOf(listOf(54.0, 24.0), listOf(54.1, 24.0), listOf(54.1, 24.1))) =
        VenueGeofence("geofence-${remoteId ?: "-"}", "Gallery 303", polygon, null, remoteId = remoteId, geofenceType = "gallery")

    @Test
    fun joinsTheGeofenceAndThePoiByTheGalleryId() {
        val poi = VenuePlace(id = "poi-g303", title = "G303 Language", coordinate = Coordinate(24.05, 54.05), level = 3.0, remoteId = "e1d2cd21")
        val destinations = Gallery.destinations(listOf(galleryGeofence("e1d2cd21"), galleryGeofence("no-poi"), galleryGeofence(null)), listOf(poi))
        assertEquals(listOf("e1d2cd21"), destinations.keys.sorted())
        val g303 = destinations.getValue("e1d2cd21")
        assertEquals("G303 Language", g303.gallery.name)
        assertEquals(3.0, g303.gallery.level)
        assertEquals(poi.coordinate, g303.entry)
        assertEquals("poi-g303", g303.placeId)
        // GeoJSON pairs are longitude, latitude.
        assertEquals(Coordinate(24.0, 54.0), g303.gallery.ring.first())
    }

    @Test
    fun aGeofenceWithoutAnOutlineIsLeftOut() {
        val poi = VenuePlace(id = "poi", title = "G303 Language", coordinate = Coordinate(24.0, 54.0), level = 3.0, remoteId = "e1d2cd21")
        assertTrue(Gallery.destinations(listOf(galleryGeofence("e1d2cd21", polygon = null)), listOf(poi)).isEmpty())
    }
}

/** Answers every leg with no route and records where each leg ends. */
private class RecordingRouting : JourneyRouting {
    val destinations = mutableListOf<MapCoordinate>()

    override suspend fun route(origin: MapCoordinate, originFloor: FloorKey, destination: MapCoordinate, destinationFloor: FloorKey): ComputedRoute? = null

    override suspend fun route(origin: MapCoordinate, originFloor: FloorKey, destination: MapCoordinate, destinationFloor: FloorKey, options: RouteOptions): RouteResult {
        destinations += destination
        return RouteResult.NoRoute(NoRouteReason.Disconnected)
    }
}
