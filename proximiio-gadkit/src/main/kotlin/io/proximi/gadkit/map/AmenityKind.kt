package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate

/**
 * The amenity shortcuts and legend of the map (Figma "MAP Functionality Approach 02",
 * node 10889:116263): Women's restroom, Men's restroom, Café, Help Desk, Prayer Room,
 * Stairs, Elevator, Museum Shop.
 *
 * A place matches a kind by its amenity id or its title. The venue data is still being
 * built, so both are checked.
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

    internal fun matches(place: VenuePlace): Boolean = matches("${place.amenityId ?: ""} ${place.title}".lowercase())

    /** [text] is the lower-case amenity id and title. */
    private fun matches(text: String): Boolean {
        val hit = keywords.any { text.contains(it) }
        // "women's restroom" contains "men's"; exclude it.
        return if (this == MENS_RESTROOM) hit && !WOMENS_RESTROOM.matches(text) else hit
    }

    /**
     * The nearest place of this kind. Places on the visitor's level come first; distance is
     * straight-line. The route gives the walking distance.
     */
    internal fun nearest(places: List<VenuePlace>, from: Coordinate?, level: Double?): VenuePlace? {
        val matching = places.filter(::matches)
        if (from == null) return matching.firstOrNull()
        val sameLevel = matching.filter { level != null && VenueLevel.same(it.level, level) }
        return VenuePlace.nearest(from, null, sameLevel.ifEmpty { matching })
    }

    internal companion object {
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
