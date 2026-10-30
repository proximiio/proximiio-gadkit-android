package io.proximi.gadkit.map

import io.proximi.sdk.core.model.ProximiioAmenity
import io.proximi.gadkit.Coordinate

/**
 * The amenity shortcuts and legend of the map (Figma "MAP Functionality Approach 02",
 * node 10889:116263): Women's restroom, Men's restroom, Café, Help Desk, Prayer Room,
 * Stairs, Elevator, Museum Shop.
 *
 * An amenity has a kind when its title names it ("Prayer Room", "Women's Restroom"). A place matches
 * a kind by its amenity ([VenuePlace.amenityKind]), not by its own title: the prayer room shortcut
 * led to the artwork "Endless Prayers XIII" (PM, Monday "Amenity search & Map legend views",
 * 2026-10-28).
 *
 * [titleKey] is the string resource of the title; [icon] names the design's icon (`icons-svg`,
 * iOS `GADIcon`).
 */
internal enum class AmenityKind(
    internal val titleKey: String,
    internal val icon: String,
    /** Lower-case fragments matched against the amenity id and the title. */
    private val keywords: List<String>,
) {
    /** "Women’s restroom". */
    WOMENS_RESTROOM("amenity_womens_restroom", "femaleToilet", listOf("women", "female", "ladies")),

    /** "Men’s restroom". */
    MENS_RESTROOM("amenity_mens_restroom", "maleToilet", listOf("men's", "mens", "male", "gents")),

    /** "Café". */
    CAFE("amenity_cafe", "cafe", listOf("cafe", "café", "coffee")),

    /** "Help Desk". */
    HELP_DESK("amenity_help_desk", "info", listOf("help", "welcome desk", "information", "info")),

    /** "Prayer Room". */
    PRAYER_ROOM("amenity_prayer_room", "prayerRoom", listOf("prayer")),

    /** "Stairs". */
    STAIRS("amenity_stairs", "stairs", listOf("stair")),

    /** "Elevator". */
    ELEVATOR("amenity_elevator", "elevator", listOf("elevator", "lift")),

    /** "Museum Shop". */
    MUSEUM_SHOP("amenity_museum_shop", "shop", listOf("shop", "store")),
    ;

    /** Whether the place's amenity is of this kind. */
    internal fun matches(place: VenuePlace): Boolean = place.amenityKind == this

    /** [text] is the lower-case amenity id and title. */
    private fun matches(text: String): Boolean {
        val hit = keywords.any { text.contains(it) }
        // "women's restroom" contains "men's"; exclude it.
        return if (this == MENS_RESTROOM) hit && !WOMENS_RESTROOM.matches(text) else hit
    }

    /**
     * The nearest place of this kind in the venue of the nearest one (`place_id`). In that venue,
     * places on the visitor's level come first; distance is straight-line. The route gives the
     * walking distance. The venue comes first because the GAD data has a café on level 1 of the DCT
     * office, 11 km away, and the Guggenheim café on level 0 (2026-10-15). Pure; covered by tests.
     */
    internal fun nearest(places: List<VenuePlace>, from: Coordinate?, level: Double?): VenuePlace? =
        AmenityShortcut.nearestOf(places.filter(::matches), from, level)

    internal companion object {
        /** The kind of each amenity that has one, by amenity id. Pure; covered by tests. */
        fun kinds(amenities: List<ProximiioAmenity>): Map<String, AmenityKind> = buildMap {
            for (amenity in amenities) kind(amenity.id, amenity.title)?.let { putIfAbsent(amenity.id, it) }
        }

        /**
         * The kind an amenity's id and title name, or `null`. The data's variants for a visited stop
         * or a destination ("Womens restroom visited") are left out. Pure; covered by tests.
         */
        fun kind(forAmenityId: String, title: String?): AmenityKind? {
            val text = "$forAmenityId ${title ?: ""}".lowercase()
            if (listOf("visited", "destination", "distination").any { text.contains(it) }) return null
            return entries.firstOrNull { it.matches(text) }
        }

        /** Shortcuts shown as chips on the map. Stairs and Elevator are legend entries only. */
        internal val SHORTCUTS: List<AmenityKind> = listOf(WOMENS_RESTROOM, MENS_RESTROOM, CAFE, HELP_DESK, PRAYER_ROOM, MUSEUM_SHOP)
    }
}
