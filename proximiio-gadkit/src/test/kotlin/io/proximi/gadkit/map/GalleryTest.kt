package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.visit.VisitPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of guggenheim-ios `GalleryTests`. */
class GalleryTest {
    /** A 0.001° square on level 1, in GeoJSON [longitude, latitude] order. */
    private fun geofence(id: String, name: String?, floor: String?) = VenueGeofence(
        id = id,
        name = name,
        polygon = listOf(listOf(54.400, 24.500), listOf(54.401, 24.500), listOf(54.401, 24.501), listOf(54.400, 24.501)),
        floorId = floor,
    )

    @Test
    fun polygonGeofencesWithANameAndFloorBecomeGalleries() {
        val galleries = Gallery.all(
            geofences = listOf(geofence("g", "Gallery 101", "f1"), geofence("x", null, "f1"), geofence("y", "Unknown floor", "f9")),
            levels = mapOf("f1" to 1.0),
        )
        assertEquals(listOf("g"), galleries.map { it.id })
        assertEquals(Coordinate(24.5, 54.4), galleries[0].ring[0])
    }

    @Test
    fun containsChecksTheOutlineAndTheLevel() {
        val gallery = Gallery.all(listOf(geofence("g", "G", "f1")), mapOf("f1" to 1.0)).first()
        val inside = Coordinate(24.5005, 54.4005)
        assertTrue(gallery.contains(inside, 1.0))
        assertTrue(gallery.contains(inside, null))
        assertFalse(gallery.contains(inside, 2.0))
        assertFalse(gallery.contains(Coordinate(24.502, 54.4005), 1.0))
    }

    @Test
    fun levelColours() {
        assertEquals(0xFF6FA6FF, Gallery.colors(1.0).destination)
        assertEquals(0xFFFFF291, Gallery.colors(3.0).visited)
    }

    @Test
    fun aHeaderPrecedesEachRunOfStopsInOneSection() {
        val places = listOf("a", "b", "c", "d", "e").map { VenuePlace(id = it, title = it, coordinate = Coordinate(24.5, 54.4), level = 1.0) }
        val stops = VisitPlan.journey(listOf("a", "b", "c", "d", "e"), places).stops
        val section = mapOf("a" to "G1", "b" to "G1", "c" to "G2", "d" to "G1")
        val rows = VisitPlan.listRows(stops) { section[it.id] }
        assertEquals(listOf("header-a", "a", "b", "header-c", "c", "header-d", "d", "e"), rows.map { it.id })
        assertEquals(listOf("G1", "G2", "G1"), rows.filterIsInstance<VisitPlan.ListRow.Header>().map { it.title })
    }

    @Test
    fun listRowIdsAreUnique() {
        val places = listOf("a", "b", "c").map { VenuePlace(id = it, title = it, coordinate = Coordinate(24.5, 54.4), level = 1.0) }
        val stops = VisitPlan.journey(listOf("a", "b", "c"), places).stops
        val rows = VisitPlan.listRows(stops) { "G1" }
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
    }
}

/** Port of ProximiioGADKit `GalleryNameTests` (0.12.0). */
class GalleryNameTest {
    private fun geofence(id: String, name: String?, remoteId: String?, type: String = "gallery") =
        VenueGeofence(id, name, polygon = null, floorId = null, remoteId = remoteId, geofenceType = type)

    /** GAD data 2026-10-15: the artworks of "Gallery 403" (level 4) name it in `metadata.gallery_id`. */
    @Test
    fun galleryGeofencesNameGalleriesByTheirRemoteId() {
        val names = Gallery.names(
            listOf(
                geofence("a", "Gallery 403", "e07fe5d7"),
                geofence("b", "Gallery 102", ""),
                geofence("c", null, "x"),
                geofence("d", "Untitled", "GAD.2010.00033", type = "artwork"),
            ),
        )
        assertEquals(mapOf("e07fe5d7" to "Gallery 403"), names)
    }
}

/** Port of guggenheim-ios `GalleryOutlineTests`. */
class GalleryOutlineTest {
    @Test
    fun polygonOuterRingIsReadInGeoJSONOrder() {
        val square = listOf(
            listOf(listOf(54.400, 24.500), listOf(54.401, 24.500), listOf(54.401, 24.501), listOf(54.400, 24.500)),
        )
        val ring = Gallery.outerRing("Polygon", square)!!
        assertEquals(4, ring.size)
        assertEquals(Coordinate(24.5, 54.401), ring[1])
        assertEquals(4, Gallery.outerRing("MultiPolygon", listOf(square))?.size)
        assertNull(Gallery.outerRing("Point", square))
    }
}
