package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.explore.ExploreCatalog
import io.proximi.sdk.core.model.JsonValue
import io.proximi.sdk.core.model.ProximiioAmenity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Port of ProximiioGADKit `AmenityListingTests`: the amenity metadata that chooses and orders the
 * search shortcuts and the Explore Museum facilities (PM, Monday "Amenity search & Map legend
 * views", 2026-10-27). Metadata and its keys are optional.
 */
class AmenityListingTest {
    private fun amenity(id: String, title: String, metadata: Map<String, JsonValue>? = null) =
        ProximiioAmenity(id = id, title = title, metadata = metadata?.let { JsonValue.Object(it) })

    private fun place(id: String, title: String, amenity: String) =
        VenuePlace(id = id, title = title, coordinate = Coordinate(24.54, 54.40), level = 1.0, amenityId = amenity)

    private fun text(value: String) = JsonValue.String(value)

    private fun number(value: Double) = JsonValue.Number(value)

    private val places = VenuePlace.withAmenityKinds(
        listOf(
            place("p-women", "Women's restroom", "a-women"), place("p-men", "Men's restroom", "a-men"),
            place("p-cafe", "Cafe", "a-cafe"), place("p-help", "Help Desk", "a-help"),
            place("p-prayer", "Prayer Room", "a-prayer"), place("p-shop", "Museum Shop", "a-shop"),
            place("p-stairs", "Staircase", "a-stairs"),
        ),
        listOf(
            amenity("a-women", "Women's Restroom"), amenity("a-men", "Men's Restroom"), amenity("a-cafe", "Cafe"),
            amenity("a-help", "Helpdesk"), amenity("a-prayer", "Prayer Room"), amenity("a-shop", "Museum Shop"),
            amenity("a-stairs", "Staircase"),
        ),
    )

    /** Without metadata the shortcuts are the built-in ones, in the built-in order. */
    @Test
    fun withoutMetadataTheShortcutsStay() {
        val listing = AmenityListing.search(listOf(amenity("a-women", "Women's Restroom"), amenity("a-cafe", "Cafe", emptyMap())))
        assertEquals(AmenityKind.SHORTCUTS, listing.shortcuts(places))
        assertEquals(AmenityKind.SHORTCUTS, AmenityListing.ALL.shortcuts(places))
    }

    /**
     * Five amenities with the flag true, in the data's order; Help Desk without metadata is left out
     * (PM, Monday "Amenity search & Map legend views", 2026-10-28).
     */
    @Test
    fun theFlaggedAmenitiesInTheDataOrderAreTheOnlyShortcuts() {
        val listing = AmenityListing.search(
            listOf(
                amenity("a-prayer", "Prayer Room", mapOf("in_search" to text("true"), "search_order" to number(4.0))),
                amenity("a-women", "Women's Restroom", mapOf("in_search" to text("true"), "search_order" to number(1.0))),
                amenity("a-shop", "Museum Shop", mapOf("in_search" to text("true"), "search_order" to number(5.0))),
                amenity("a-cafe", "Cafe", mapOf("in_search" to text("true"), "search_order" to number(3.0))),
                amenity("a-men", "Men's Restroom", mapOf("in_search" to text("true"), "search_order" to text("2"))),
                amenity("a-help", "Helpdesk"),
            ),
        )
        assertEquals(
            listOf(AmenityKind.WOMENS_RESTROOM, AmenityKind.MENS_RESTROOM, AmenityKind.CAFE, AmenityKind.PRAYER_ROOM, AmenityKind.MUSEUM_SHOP),
            listing.shortcuts(places),
        )
    }

    /** The GAD data on 2026-10-28: only Museum Shop has `in_search` true, so it is the only shortcut. */
    @Test
    fun oneFlaggedAmenityIsTheOnlyShortcut() {
        val listing = AmenityListing.search(
            listOf(
                amenity("a-shop", "Museum Shop", mapOf("in_search" to text("true"), "search_order" to number(5.0))),
                amenity("a-prayer", "Prayer Room"),
            ),
        )
        assertEquals(listOf(AmenityKind.MUSEUM_SHOP), listing.shortcuts(places))
        assertNull(listing.rank("a-prayer"))
        assertNull(listing.rank(null))
    }

    /** Without a flag true, a flag false leaves the amenity out of the built-in shortcuts. A flag true puts in one the built-in list does not have. */
    @Test
    fun theFlagLeavesOutOrPutsIn() {
        val without = AmenityListing.search(listOf(amenity("a-help", "Helpdesk", mapOf("in_search" to JsonValue.Bool(false)))))
        assertEquals(AmenityKind.SHORTCUTS - AmenityKind.HELP_DESK, without.shortcuts(places))
        assertNull(without.rank("a-help"))
        assertEquals(Double.MAX_VALUE, without.rank("a-unknown"))
        assertEquals(Double.MAX_VALUE, without.rank(null))

        val stairs = AmenityListing.search(listOf(amenity("a-stairs", "Staircase", mapOf("in_search" to JsonValue.Bool(true), "search_order" to number(0.0)))))
        assertEquals(listOf(AmenityKind.STAIRS), stairs.shortcuts(places))
    }

    /**
     * The facilities keys choose and order the Explore Museum's Amenities list within a level and the
     * legend. With a flag true only the flagged amenities show.
     */
    @Test
    fun theFacilitiesOrderTheExploreLists() {
        val categories = mapOf("a-shop" to "Museum Shop", "a-women" to "Women's Restroom", "a-cafe" to "Cafe", "a-men" to "Men's Restroom")
        val facilities = AmenityListing.facilities(
            listOf(
                amenity("a-shop", "Museum Shop", mapOf("in_facilities" to text("true"), "facilities_order" to number(1.0))),
                amenity("a-women", "Women's Restroom", mapOf("in_facilities" to text("false"))),
                amenity("a-elevator", "Elevator", mapOf("facilities_order" to number(2.0))),
            ),
        )
        val catalog = ExploreCatalog.of(places, emptyMap(), categories, mapOf("elevator" to "Elevator"), facilities)
        assertEquals(listOf("Museum Shop"), catalog.amenities.map { it.title })
        assertEquals(listOf("Museum Shop"), catalog.legendFacilities.map { it.title })

        val ordered = AmenityListing.facilities(
            listOf(
                amenity("a-women", "Women's Restroom", mapOf("in_facilities" to text("false"))),
                amenity("a-elevator", "Elevator", mapOf("facilities_order" to number(0.0))),
            ),
        )
        val unflagged = ExploreCatalog.of(places, emptyMap(), categories, mapOf("elevator" to "Elevator"), ordered)
        assertFalse(unflagged.amenities.any { it.title == "Women's restroom" })
        assertEquals("Elevator", unflagged.legendFacilities.firstOrNull()?.title)
        assertFalse(unflagged.legendFacilities.any { it.title == "Women's Restroom" })
    }
}
