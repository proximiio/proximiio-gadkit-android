package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.visit.VisitPlan
import io.proximi.sdk.core.model.ProximiioAmenity
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
        assertEquals(0xFF2DE09C, Gallery.colors(0.0).destination)
        assertEquals(0xFF36A8FE, Gallery.colors(1.0).destination)
        assertEquals(0xFFDEADFF, Gallery.colors(2.0).visited)
        assertEquals(0xFFFFEA8E, Gallery.colors(3.0).visited)
        assertEquals(0xFFFF7342, Gallery.colors(4.0).destination)
    }

    /**
     * The visitor is in a gallery while they are in its geofence, also outside the polygon the map
     * fills (GAD PM, Monday "Gallery blue colour", 2026-11-17; iOS 1.59.0). A gallery without a
     * geofence uses its own outline.
     */
    @Test
    fun theVisitorsGalleryIsTheOneWhoseGeofenceHoldsThem() {
        fun ring(south: Double, west: Double, north: Double, east: Double) =
            listOf(Coordinate(south, west), Coordinate(south, east), Coordinate(north, east), Coordinate(north, west))
        // Polygon A: longitude 54.400 to 54.401. Its geofence reaches 54.4012.
        val polygonA = Gallery("polygon-a", "A", 1.0, ring(24.500, 54.400, 24.501, 54.401))
        val geofenceA = Gallery("a", "A", 1.0, ring(24.500, 54.3998, 24.501, 54.4012))
        // Polygon B starts at 54.4011, inside the geofence of A. Its geofence is the same outline.
        val polygonB = Gallery("polygon-b", "B", 1.0, ring(24.500, 54.4011, 24.501, 54.402))
        val geofenceB = Gallery("b", "B", 1.0, polygonB.ring)
        val outline = Gallery("outline", "C", 1.0, ring(24.510, 54.400, 24.511, 54.401))
        val entry = Coordinate(24.5005, 54.4005)
        val destinations = listOf(
            GalleryDestination(geofenceA, entry, "poi-a", polygonA),
            GalleryDestination(geofenceB, entry, "poi-b", polygonB),
        )
        val galleries = listOf(polygonA, polygonB, outline)
        fun gallery(longitude: Double, latitude: Double = 24.5005, level: Double? = 1.0, current: String? = null) =
            Gallery.ofVisitor(Coordinate(latitude, longitude), level, galleries, destinations, current)?.id
        assertEquals("polygon-a", gallery(54.4005))
        // Outside the polygon of A, inside its geofence.
        assertEquals("polygon-a", gallery(54.40105))
        assertEquals("polygon-a", gallery(54.3999))
        // In the geofence of A and in the polygon of B: the polygon decides.
        assertEquals("polygon-b", gallery(54.40115))
        assertEquals("polygon-b", gallery(54.40115, current = "polygon-a"))
        assertEquals("polygon-b", gallery(54.4015))
        // Outside every geofence, on another floor, and a gallery with an outline only.
        assertNull(gallery(54.4030))
        assertNull(gallery(54.4005, level = 2.0))
        assertEquals("outline", gallery(54.4005, latitude = 24.5105))
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
    private fun feature(id: String, type: String, level: Double, levels: Any?, geometry: String = "Point", amenity: String? = null) = VenueFeature(
        id = id,
        type = type,
        geometry = Geometry(geometry, listOf(54.399, 24.5397)),
        properties = buildMap {
            put("type", type)
            put("level", level)
            if (levels != null) put("levels", levels)
            if (amenity != null) put("amenity", amenity)
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

    /** Port of iOS `LevelChangerIconTests.aRampAndAHillAreLevelChangers` (1.47.0). */
    @Test
    fun aRampAndAHillAreLevelChangers() {
        assertEquals(listOf("elevator", "staircase", "escalator", "ramp", "hill"), LevelChangerPlace.FEATURE_TYPES)
        val changers = LevelChangerPlace.all(
            listOf(
                feature("ramp", "ramp", 0.0, listOf(0.0, 1.0), amenity = "org:ramp"),
                feature("hill", "hill", 1.0, null, amenity = ""),
            ),
        )
        assertEquals(listOf("ramp", "hill"), changers.map { it.id })
        assertEquals(listOf("org:ramp", null), changers.map { it.amenityId })
        assertTrue(changers[0].serves(0.0) && changers[0].serves(1.0))
    }

    /** Port of iOS `LevelChangerIconTests.onlyAnAmenityThatIsNeitherStairsNorALiftShowsItsOwnIcon` (1.47.0). */
    @Test
    fun onlyAnAmenityThatIsNeitherStairsNorALiftShowsItsOwnIcon() {
        assertTrue(LevelChangerPlace.showsAmenityIcon("org:1", "Ramp"))
        assertTrue(LevelChangerPlace.showsAmenityIcon("org:2", "Escalator UP"))
        assertTrue(LevelChangerPlace.showsAmenityIcon("org:3", null))
        assertFalse(LevelChangerPlace.showsAmenityIcon("org:4", "Staircase"))
        assertFalse(LevelChangerPlace.showsAmenityIcon("org:5", "Elevator"))
        assertFalse(LevelChangerPlace.showsAmenityIcon("org:lift", null))
        val amenities = listOf(
            ProximiioAmenity(id = "org:1", title = "Ramp"),
            ProximiioAmenity(id = "org:4", title = "Staircase"),
        )
        val icons = mapOf("org:1" to "ramp icon", "org:4" to "stairs icon", "org:9" to "unknown icon")
        assertEquals(mapOf("org:1" to "ramp icon"), LevelChangerPlace.amenityIcons(amenities, icons))
    }

    /** Each gallery style has its own style image (PM, Monday "Gallery numbers in gallery navigation", 2026-10-28). */
    @Test
    fun eachGalleryStyleHasItsOwnImage() {
        val names = JourneyMapLayers.GalleryMarker.Style.entries.map { style ->
            JourneyMapLayers.GalleryMarker(Coordinate(0.0, 0.0), 3.0, "G303 Language", style).imageName
        }
        assertEquals(3, names.toSet().size)
    }
}
