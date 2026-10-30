package io.proximi.gadkit.map

import androidx.compose.ui.graphics.ImageBitmap
import io.proximi.gadkit.Coordinate

/**
 * One amenity of the maps' search, as iOS `AmenityShortcut` (1.26.0): a kind of [AmenityKind], with
 * the design's title and icon, or an amenity the data puts in the search (`metadata.in_search`) that
 * is none of the kinds, with the amenity's own title and icon. The galleries, the artworks and
 * Learning & Engagement had the flag and did not show (PM, 2026-10-29).
 */
internal sealed interface AmenityShortcut {
    val id: String

    data class Kind(val kind: AmenityKind) : AmenityShortcut {
        override val id: String get() = "kind:${kind.name}"

        override fun matches(place: VenuePlace): Boolean = kind.matches(place)
    }

    /** An amenity of the venue data: its id, its title in the app language and its icon. */
    data class Amenity(val amenityId: String, val title: String, val icon: ImageBitmap?) : AmenityShortcut {
        override val id: String get() = amenityId

        override fun matches(place: VenuePlace): Boolean = place.amenityId == amenityId
    }

    /** A place of a kind matches by its amenity's kind; a place of an amenity from the data by its amenity id. */
    fun matches(place: VenuePlace): Boolean

    /** The nearest matching place, as [AmenityKind.nearest]. Pure; covered by tests. */
    fun nearest(places: List<VenuePlace>, from: Coordinate?, level: Double?): VenuePlace? = nearestOf(places.filter(::matches), from, level)

    companion object {
        /**
         * The nearest of [matching] in the venue of the nearest one (`place_id`). In that venue, places
         * on the visitor's level come first; distance is straight-line. The route gives the walking
         * distance. The venue comes first because the GAD data has a café on level 1 of the DCT office,
         * 11 km away, and the Guggenheim café on level 0 (2026-10-15).
         */
        fun nearestOf(matching: List<VenuePlace>, from: Coordinate?, level: Double?): VenuePlace? {
            if (from == null) return matching.firstOrNull()
            val closest = VenuePlace.nearest(from, null, matching) ?: return null
            val venue = matching.filter { it.placeId == closest.placeId }
            val sameLevel = venue.filter { level != null && VenueLevel.same(it.level, level) }
            return VenuePlace.nearest(from, null, sameLevel.ifEmpty { venue })
        }
    }
}
