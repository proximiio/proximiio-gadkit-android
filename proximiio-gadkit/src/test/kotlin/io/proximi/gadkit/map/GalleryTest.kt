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

/** Port of ProximiioGADKit `GalleryNameTests` (0.13.0). */
class GalleryNameTest {
    private fun place(id: String, title: String, remoteId: String?, isArtwork: Boolean = false) =
        VenuePlace(id = id, title = title, coordinate = Coordinate(24.5, 54.4), level = 4.0, remoteId = remoteId, isArtwork = isArtwork)

    /**
     * GAD data 2026-10-15: the artworks of "G403 Weaving" (level 4) name it in `metadata.gallery_id`,
     * and the gallery POI holds that id in `remote_id`.
     */
    @Test
    fun galleryPOIsNameGalleriesByTheirRemoteId() {
        val names = Gallery.names(
            listOf(
                place("a", "G403 Weaving", "e07fe5d7"),
                place("b", "G304 Histories ", "24096077"),
                place("c", "Gallery 102", ""),
                place("d", "Café", null),
                place("e", "Untitled", "GAD.2010.00033", isArtwork = true),
            ),
        )
        assertEquals(mapOf("e07fe5d7" to "G403 Weaving", "24096077" to "G304 Histories"), names)
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

/** Port of ProximiioGADKit `LevelChangerPlaceTests` (1.4.0). */
class LevelChangerPlaceTest {
    private fun feature(id: String, type: String, level: Double, levels: Any?, geometry: String = "Point") = VenueFeature(
        id = id,
        type = type,
        geometry = Geometry(geometry, listOf(54.399, 24.5397)),
        properties = buildMap {
            put("type", type)
            put("level", level)
            if (levels != null) put("levels", levels)
        },
        level = level,
    )

    /**
     * GAD data 2026-10-15: lift 9f411e5a is a POI on level 0 with `levels` [1, 2, 3, 4]. The iOS SDK
     * router (6.0.0-beta.60) does not use it on level 0, so it is drawn on levels 1 to 4 only.
     */
    @Test
    fun aLevelChangerServesTheFloorsOfItsLevels() {
        val changers = LevelChangerPlace.all(
            listOf(
                feature("lift", "elevator", 0.0, listOf(1.0, 2.0, 3.0, 4.0)),
                feature("stairs", "staircase", 1.0, null),
                feature("cafe", "poi", 0.0, listOf(1.0)),
                feature("outline", "elevator", 1.0, null, geometry = "MultiPolygon"),
            ),
        )
        assertEquals(listOf("lift", "stairs"), changers.map { it.id })
        val lift = changers.first()
        assertEquals(Coordinate(24.5397, 54.399), lift.coordinate)
        assertTrue(listOf(1.0, 2.0, 4.0).all { lift.serves(it) })
        assertFalse(lift.serves(0.0) || lift.serves(5.0))
        assertTrue(changers[1].serves(1.0) && !changers[1].serves(2.0))
    }

    /** Same reading of `properties.levels` as the iOS SDK's `LevelChangerExtractor.levels(from:)`. */
    @Test
    fun levelsAreNumbersOrNumericStrings() {
        val changers = LevelChangerPlace.all(
            listOf(
                feature("strings", "elevator", 0.0, listOf("1", " 2 ", "x", true)),
                feature("single", "staircase", 0.0, 3.0),
                feature("empty", "escalator", 2.0, emptyList<Any>()),
                feature("unreadable", "elevator", 1.0, listOf("ground")),
            ),
        )
        assertEquals(listOf(listOf(1.0, 2.0), listOf(3.0), listOf(2.0), listOf(1.0)), changers.map { it.levels })
    }
}
