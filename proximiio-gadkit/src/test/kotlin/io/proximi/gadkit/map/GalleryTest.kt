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
    fun consecutiveStopsInOneGalleryFormAGroup() {
        val places = listOf("a", "b", "c", "d").map { VenuePlace(id = it, title = it, coordinate = Coordinate(24.5, 54.4), level = 1.0) }
        val stops = VisitPlan.journey(listOf("a", "b", "c", "d"), places).stops
        val gallery = mapOf("a" to "G1", "b" to "G1", "c" to "G2", "d" to "G1")
        val groups = VisitPlan.galleryGroups(stops) { gallery[it.id] }
        assertEquals(listOf("G1", "G2", "G1"), groups.map { it.gallery })
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), groups.map { group -> group.stops.map { it.id } })
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
