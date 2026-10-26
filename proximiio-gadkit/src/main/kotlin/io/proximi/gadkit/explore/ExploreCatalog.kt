package io.proximi.gadkit.explore

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GalleryDestination
import io.proximi.gadkit.map.StandardTitleOrder
import io.proximi.gadkit.map.VenuePlace
import kotlin.math.roundToLong

/*
 * The lists of the Explore Museum map (Figma "Explore Museum", node 12129:102006; PM screens
 * 2026-10-23), each grouped by level, as iOS `ExploreCatalog`:
 *
 * - Spaces: the galleries. The gallery geofence (`geofence_type` gallery) gives the outline; the
 *   POI with the same `remote_id` gives the name, the level, the category (its amenity, such as
 *   "Thematic Gallery") and the description (`Gallery.destinations`).
 * - Highlights: the artworks that list the highlight amenity in `included_amenities`.
 * - Amenities: the POIs of an `AmenityKind` other than stairs and lifts.
 * - The map legend: each amenity a museum POI uses, other than artworks. Galleries are the amenities
 *   of the spaces and the amenities named "… Gallery", "… Exhibition" or "Learning …"; the others are
 *   facilities, with "Elevator" and "Stairs" when the venue has lifts or staircases
 *   (`LevelChangerPlace`, features of their own, not POIs).
 *
 * The GAD organisation also holds the DCT office. Only the POIs with the `place_id` of the gallery
 * POIs are listed.
 */

/** A row of the Explore Museum lists and the place its card shows. */
internal data class ExploreItem(
    /** The POI's id. */
    val id: String,
    val kind: Kind,
    val title: String,
    /** The lines under the title: a space's category and description; a highlight's artist and gallery. */
    val lines: List<String>,
    val level: Double,
    val coordinate: Coordinate,
    /** A space's outline. */
    val gallery: Gallery? = null,
    /** A space's category, the title of its POI's amenity. */
    val category: String? = null,
    /** The POI's amenity, for its icon in the venue data. */
    val amenityId: String? = null,
) {
    sealed interface Kind {
        data object Space : Kind
        data object Highlight : Kind
        data class Amenity(val kind: AmenityKind) : Kind
    }

    /** The symbol of a space's category ([symbol]). */
    enum class Symbol { PALETTE, GRID, STAR, COLUMNS }

    /** The points the map frames for this item: the outline of a space, else the POI. */
    val framedPoints: List<Coordinate> get() = gallery?.ring?.takeIf { it.isNotEmpty() } ?: listOf(coordinate)

    companion object {
        /**
         * The symbol of a space's [category]: a palette for a single-artist gallery, a grid for a
         * thematic one, a star for Learning & Engagement, columns for any other space. Pure; covered
         * by tests.
         */
        fun symbol(category: String?): Symbol {
            val text = category?.lowercase().orEmpty()
            return when {
                "single" in text -> Symbol.PALETTE
                "thematic" in text -> Symbol.GRID
                "learning" in text -> Symbol.STAR
                else -> Symbol.COLUMNS
            }
        }
    }
}

/** An amenity of the map legend. */
internal data class ExploreLegendEntry(val amenityId: String, val title: String)

/** The Explore Museum lists of the museum. */
internal data class ExploreCatalog(
    val spaces: List<ExploreItem> = emptyList(),
    val highlights: List<ExploreItem> = emptyList(),
    val amenities: List<ExploreItem> = emptyList(),
    val legendGalleries: List<ExploreLegendEntry> = emptyList(),
    val legendFacilities: List<ExploreLegendEntry> = emptyList(),
) {
    /** The levels with a space, a highlight or an amenity, lowest first. */
    val levels: List<Double> get() = (spaces + amenities + highlights).map { rounded(it.level) }.distinct().sorted()

    /** Every item of the catalogue, for a tap on the map. */
    val all: List<ExploreItem> get() = spaces + highlights + amenities

    /** The space whose outline holds [coordinate] on [level]. */
    fun space(coordinate: Coordinate, level: Double): ExploreItem? = spaces.firstOrNull { it.gallery?.contains(coordinate, level) == true }

    /** The items of one level. */
    data class LevelGroup(val level: Double, val items: List<ExploreItem>)

    companion object {
        /**
         * [categories] maps an amenity id to its title. [levelChangerTitles] names the venue's kinds
         * of lift and staircase by feature type (`elevator`, `staircase`), in the app's language.
         * Pure; covered by tests.
         */
        fun of(
            places: List<VenuePlace>,
            destinations: Map<String, GalleryDestination>,
            categories: Map<String, String>,
            levelChangerTitles: Map<String, String> = emptyMap(),
        ): ExploreCatalog {
            val byId = LinkedHashMap<String, VenuePlace>().apply { places.forEach { putIfAbsent(it.id, it) } }
            val museum = museumPlaceId(destinations, byId)
            val inMuseum = { place: VenuePlace -> museum == null || place.placeId == museum }

            val spaces = destinations.values.mapNotNull { destination ->
                val poi = byId[destination.placeId]?.takeIf(inMuseum) ?: return@mapNotNull null
                val category = poi.amenityId?.let { categories[it] }
                val line = listOfNotNull(category, poi.details).joinToString(". ")
                ExploreItem(
                    id = poi.id, kind = ExploreItem.Kind.Space, title = destination.gallery.name,
                    lines = if (line.isEmpty()) emptyList() else listOf(line), level = poi.level,
                    coordinate = destination.entry, gallery = destination.gallery, category = category,
                    amenityId = poi.amenityId,
                )
            }.sortedWith(ORDER)

            val galleryNames = buildMap { spaces.forEach { item -> item.gallery?.let { putIfAbsent(it.id, item.title) } } }
            val highlights = places.filter { it.isHighlight && inMuseum(it) }.map { place ->
                ExploreItem(
                    id = place.id, kind = ExploreItem.Kind.Highlight, title = place.title,
                    lines = listOfNotNull(place.artist, place.galleryId?.let { galleryNames[it] }),
                    level = place.level, coordinate = place.coordinate,
                )
            }.sortedWith(ORDER)

            val amenities = places.mapNotNull { place ->
                if (place.isArtwork || !inMuseum(place)) return@mapNotNull null
                val kind = AmenityKind.entries.firstOrNull { it.matches(place) } ?: return@mapNotNull null
                if (kind == AmenityKind.STAIRS || kind == AmenityKind.ELEVATOR) return@mapNotNull null
                ExploreItem(place.id, ExploreItem.Kind.Amenity(kind), place.title, emptyList(), place.level, place.coordinate, amenityId = place.amenityId)
            }.sortedWith(ORDER)

            val galleryAmenities = spaces.mapNotNull { it.amenityId }.toSet()
            val used = places.filter { !it.isArtwork && inMuseum(it) }.mapNotNull { it.amenityId }.toSet()
            val entries = used.mapNotNull { id -> categories[id]?.let { ExploreLegendEntry(id, it) } }.sortedWith(LEGEND_ORDER)
            val isGallery = { entry: ExploreLegendEntry -> entry.amenityId in galleryAmenities || GALLERY_TITLE.containsMatchIn(entry.title) }
            val titles = entries.map { it.title.lowercase() }.toSet()
            val changers = listOf("elevator", "staircase").mapNotNull { type ->
                levelChangerTitles[type]?.takeIf { it.lowercase() !in titles }?.let { ExploreLegendEntry("level-changer-$type", it) }
            }
            val facilities = (entries.filterNot(isGallery) + changers).sortedWith(LEGEND_ORDER)

            return ExploreCatalog(spaces, highlights, amenities, entries.filter(isGallery), facilities)
        }

        /** [items] by level, lowest first. Pure; covered by tests. */
        fun byLevel(items: List<ExploreItem>): List<LevelGroup> =
            items.groupBy { rounded(it.level) }.toSortedMap().map { (level, group) -> LevelGroup(level, group) }

        /** The `place_id` most of the gallery POIs share: the museum. A tie goes to the smaller id. */
        private fun museumPlaceId(destinations: Map<String, GalleryDestination>, places: Map<String, VenuePlace>): String? =
            destinations.values.mapNotNull { places[it.placeId]?.placeId }
                .groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key

        /** Level, then title. */
        private val ORDER: Comparator<ExploreItem> = compareBy<ExploreItem> { it.level }.thenBy(StandardTitleOrder) { it.title }

        private val LEGEND_ORDER: Comparator<ExploreLegendEntry> = compareBy(StandardTitleOrder) { it.title }

        /** The title of an amenity the legend counts as a gallery. */
        private val GALLERY_TITLE = Regex("gallery|exhibition|learning", RegexOption.IGNORE_CASE)

        private fun rounded(level: Double): Double = level.roundToLong().toDouble()
    }
}
