package io.proximi.gadkit.visit

import io.proximi.gadkit.map.GalleryFill
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.UiText
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GalleryDestination
import io.proximi.gadkit.map.VenueGeofence
import io.proximi.gadkit.map.VenuePlace
import io.proximi.map.core.JourneyStop as MapJourneyStop
import io.proximi.gadkit.map.Geometry
import io.proximi.gadkit.map.VenueFeature
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

/** Port of ProximiioGADKit `GalleryFirstTests` of 1.15.0 to 1.20.0: gallery polygons, the gallery list and visited galleries. */
class GalleryPolygonTest {
    private val square = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 1.0), Coordinate(1.0, 1.0), Coordinate(1.0, 0.0))

    private fun geofence(remoteId: String) = VenueGeofence(
        "geofence-$remoteId", "Gallery 303",
        listOf(listOf(54.0, 24.0), listOf(54.1, 24.0), listOf(54.1, 24.1), listOf(54.0, 24.1)),
        null, remoteId = remoteId, geofenceType = "gallery",
    )

    private fun polygon(id: String, level: Double, galleryId: String? = null): VenueFeature {
        val ring = listOf(listOf(54.02, 24.02), listOf(54.08, 24.02), listOf(54.08, 24.08), listOf(54.02, 24.08))
        val metadata: Map<String, Any?> = galleryId?.let { mapOf("gallery_id" to it) } ?: emptyMap()
        return VenueFeature(id, "gallery", Geometry("Polygon", listOf(ring)), mapOf("metadata" to metadata), level)
    }

    /**
     * The galleries with a `gallery_id` come first, named by their POI; an outline that holds one of
     * their POIs is left out, another outline stays (PM, Monday "Visited gallery visualization",
     * 2026-10-28: after the POIs were renamed, only three galleries were known).
     */
    @Test
    fun theGalleriesWithAnIdComeFirstAndTheirOutlinesAreNotRepeated() {
        val far = square.map { Coordinate(it.latitude + 5, it.longitude) }
        val g101 = GalleryDestination(Gallery("e984", "G101 Pop Culture", 1.0, square), Coordinate(0.5, 0.5), "poi-g101")
        val merged = Gallery.merged(
            listOf(g101),
            listOf(Gallery("feature-101", "Gallery 101", 1.0, square), Gallery("feature-306", "Gallery 306", 3.0, far)),
        )
        assertEquals(listOf("G101 Pop Culture", "Gallery 306"), merged.map { it.name })
        assertEquals("e984", merged.first().id)
    }

    /** PM, Monday "Explore Museum view: gallery highlighting", 2026-10-26: the map fills the gallery's polygon, not its geofence. */
    @Test
    fun theGalleryPolygonIsTheOneWithTheGalleryIdElseTheOneAroundThePoi() {
        val poi = VenuePlace(id = "poi-g303", title = "G303 Language", coordinate = Coordinate(24.05, 54.05), level = 3.0, remoteId = "e1d2cd21")
        val around = Gallery.destinations(listOf(geofence("e1d2cd21")), listOf(poi), listOf(polygon("other-floor", 2.0), polygon("room", 3.0))).getValue("e1d2cd21")
        assertEquals("room", around.polygon?.id)
        assertEquals("room", around.shown.id)
        assertEquals("e1d2cd21", around.gallery.id)
        val byId = Gallery.destinations(listOf(geofence("e1d2cd21")), listOf(poi), listOf(polygon("room", 3.0), polygon("tagged", 2.0, galleryId = "e1d2cd21"))).getValue("e1d2cd21")
        assertEquals("tagged", byId.polygon?.id)
        val none = Gallery.destinations(listOf(geofence("e1d2cd21")), listOf(poi)).getValue("e1d2cd21")
        assertNull(none.polygon)
        assertEquals(none.gallery, none.shown)
    }

    /** A gallery is visited when its artworks of the journey are all visited or skipped, at least one visited (the customer, Monday "Visited gallery visualization", 2026-10-28). */
    @Test
    fun aGalleryIsVisitedWhenAllItsArtworksAreDoneOrSkipped() {
        val stops = listOf(
            "g101" to MapJourneyStop.State.DONE, "g101" to MapJourneyStop.State.SKIPPED,
            "g104" to MapJourneyStop.State.DONE, "g104" to MapJourneyStop.State.PENDING,
            "g106" to MapJourneyStop.State.SKIPPED,
            "g303" to MapJourneyStop.State.DONE,
        )
        assertEquals(setOf("g101", "g303"), VisitViewModel.visitedGalleries(stops, visitorGallery = null))
        // Not while the visitor is still in it (PM, 2026-10-29).
        assertEquals(setOf("g303"), VisitViewModel.visitedGalleries(stops, visitorGallery = "g101"))
    }

    /**
     * The gallery the visitor is in has no fill, so its artworks and furniture show; every other
     * gallery is destination, visited or the standard colour (PM, Monday "Artwork + furniture
     * polygons", 2026-10-29).
     */
    @Test
    fun everyGalleryButTheVisitorsHasAFill() {
        val visited = setOf("g101")
        assertEquals(GalleryFill.Style.VISITED, VisitViewModel.fillStyle("g101", destination = "g303", visited = visited, visitorGallery = null))
        assertEquals(GalleryFill.Style.DESTINATION, VisitViewModel.fillStyle("g303", destination = "g303", visited = visited, visitorGallery = null))
        assertEquals(GalleryFill.Style.STANDARD, VisitViewModel.fillStyle("g104", destination = "g303", visited = visited, visitorGallery = null))
        assertNull(VisitViewModel.fillStyle("g104", destination = "g303", visited = visited, visitorGallery = "g104"))
        assertNull(VisitViewModel.fillStyle("g303", destination = "g303", visited = visited, visitorGallery = "g303"))
    }
}
