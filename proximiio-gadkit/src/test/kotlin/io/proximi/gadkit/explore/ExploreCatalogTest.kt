package io.proximi.gadkit.explore

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GalleryDestination
import io.proximi.gadkit.map.VenuePlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Port of guggenheim-ios `ExploreCatalogTests`. */
class ExploreCatalogTest {
    private companion object {
        const val MUSEUM = "museum"
        const val OFFICE = "office"
        const val THEMATIC = "org:thematic"
        const val SINGLE = "org:single"

        fun place(id: String, title: String, level: Double, amenity: String? = null, placeId: String = MUSEUM, details: String? = null) =
            VenuePlace(id = id, title = title, details = details, coordinate = Coordinate(24.54, 54.40), level = level, amenityId = amenity, placeId = placeId)

        fun destination(galleryId: String, name: String, level: Double, poi: String): GalleryDestination {
            val ring = listOf(Coordinate(24.5, 54.4), Coordinate(24.5, 54.41), Coordinate(24.51, 54.41), Coordinate(24.51, 54.4))
            return GalleryDestination(Gallery(galleryId, name, level, ring), entry = Coordinate(24.505, 54.405), placeId = poi)
        }

        val catalog: ExploreCatalog = run {
            val highlight = place("art", "Tongari-kun", level = 1.0).copy(
                isArtwork = true,
                artist = "Yayoi Kusama",
                galleryId = "g202",
                includedAmenityIds = listOf(VenuePlace.HIGHLIGHT_AMENITY_ID),
            )
            val artwork = place("art2", "Untitled", level = 2.0).copy(isArtwork = true)
            val places = listOf(
                place("p303", "G303 Language", level = 3.0, amenity = THEMATIC, details = "Words and signs"),
                place("p202", "G202 Yayoi Kusama", level = 2.0, amenity = SINGLE),
                highlight, artwork,
                place("cafe", "Café", level = 0.0, amenity = "org:cafe"),
                place("lift", "Elevator 1", level = 1.0, amenity = "org:elevator"),
                place("dctPrayer", "DCT prayer room", level = 1.0, amenity = "org:prayer", placeId = OFFICE),
            )
            val destinations = mapOf(
                "g303" to destination("g303", "G303 Language", 3.0, "p303"),
                "g202" to destination("g202", "G202 Yayoi Kusama", 2.0, "p202"),
            )
            ExploreCatalog.of(places, destinations, mapOf(THEMATIC to "Thematic Gallery", SINGLE to "Single Artist Gallery"))
        }
    }

    @Test
    fun spacesAreTheGalleriesWithCategoryAndDescription() {
        val spaces = catalog.spaces
        assertEquals(listOf("G202 Yayoi Kusama", "G303 Language"), spaces.map { it.title })
        assertEquals(listOf(listOf("Single Artist Gallery"), listOf("Thematic Gallery. Words and signs")), spaces.map { it.lines })
        assertEquals("p202", spaces.first().id)
        assertEquals("g202", spaces.first().gallery?.id)
    }

    @Test
    fun highlightsAreTheArtworksWithTheHighlightAmenity() {
        assertEquals(listOf("Tongari-kun"), catalog.highlights.map { it.title })
        assertEquals(listOf("Yayoi Kusama", "G202 Yayoi Kusama"), catalog.highlights.first().lines)
    }

    @Test
    fun amenitiesLeaveOutLiftsArtworksAndOtherBuildings() {
        assertEquals(listOf("cafe"), catalog.amenities.map { it.id })
        assertEquals(ExploreItem.Kind.Amenity(AmenityKind.CAFE), catalog.amenities.first().kind)
    }

    @Test
    fun levelsAndGroupsGoFromTheLowest() {
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), catalog.levels)
        val groups = ExploreCatalog.byLevel(catalog.spaces + catalog.amenities)
        assertEquals(listOf(0.0, 2.0, 3.0), groups.map { it.level })
        assertEquals(listOf(listOf("cafe"), listOf("p202"), listOf("p303")), groups.map { group -> group.items.map { it.id } })
    }

    @Test
    fun aTapInsideAGalleryFindsItsSpaceOnItsLevel() {
        val inside = Coordinate(24.505, 54.405)
        assertEquals("p303", catalog.space(inside, level = 3.0)?.id)
        assertNull(catalog.space(inside, level = 1.0))
        assertNull(catalog.space(Coordinate(24.6, 54.5), level = 3.0))
    }

    @Test
    fun aSpaceFramesItsOutline() {
        assertEquals(4, catalog.spaces.first().framedPoints.size)
        assertEquals(1, catalog.amenities.first().framedPoints.size)
    }

    @Test
    fun theSymbolFollowsTheCategory() {
        assertEquals(ExploreItem.Symbol.PALETTE, ExploreItem.symbol("Single Artist Gallery"))
        assertEquals(ExploreItem.Symbol.GRID, ExploreItem.symbol("Thematic Gallery"))
        assertEquals(ExploreItem.Symbol.STAR, ExploreItem.symbol("Learning & Engagement"))
        assertEquals(ExploreItem.Symbol.COLUMNS, ExploreItem.symbol(null))
    }

    @Test
    fun theLegendSplitsGalleriesFromFacilitiesAndLeavesOutOtherBuildings() {
        val places = listOf(
            place("p303", "G303 Language", level = 3.0, amenity = THEMATIC),
            place("perf", "Performance hall", level = 1.0, amenity = "org:performance"),
            place("lift", "Elevator 1", level = 1.0, amenity = "org:elevator"),
            place("wc", "Women's Restroom", level = 2.0, amenity = "org:women"),
            place("dctShop", "DCT shop", level = 1.0, amenity = "org:shop", placeId = OFFICE),
            place("art", "Untitled", level = 1.0, amenity = "org:artwork").copy(isArtwork = true),
        )
        val catalog = ExploreCatalog.of(
            places,
            mapOf("g303" to destination("g303", "G303 Language", 3.0, "p303")),
            mapOf(
                THEMATIC to "Thematic Gallery", "org:performance" to "Performance Gallery", "org:elevator" to "Elevator",
                "org:women" to "Women's Restroom", "org:shop" to "Shop", "org:artwork" to "Artwork",
            ),
            levelChangerTitles = mapOf("elevator" to "Elevator", "staircase" to "Stairs"),
        )
        assertEquals(listOf("Performance Gallery", "Thematic Gallery"), catalog.legendGalleries.map { it.title })
        // The POI "Elevator" names the lift already; the staircases add "Stairs".
        assertEquals(listOf("Elevator", "Stairs", "Women's Restroom"), catalog.legendFacilities.map { it.title })
        assertEquals(THEMATIC, catalog.spaces.first().amenityId)
    }
}
