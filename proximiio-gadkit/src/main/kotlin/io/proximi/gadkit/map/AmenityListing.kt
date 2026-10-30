package io.proximi.gadkit.map

import androidx.compose.ui.graphics.ImageBitmap
import io.proximi.sdk.core.model.JsonValue
import io.proximi.sdk.core.model.ProximiioAmenity

/**
 * Which amenities a list shows, and in what order, from the amenity's `metadata`, as iOS
 * `AmenityListing` (PM, Monday "Amenity search & Map legend views", 2026-10-27):
 *
 * - `in_search` and `search_order`: the amenity shortcuts of the maps' search.
 * - `in_facilities` and `facilities_order`: the Explore Museum map's Amenities list and the
 *   facilities of its legend.
 *
 * `metadata` and both keys are optional. When an amenity has the flag `true` or "true", the list
 * shows only the amenities with the flag true (PM, Monday "Amenity search & Map legend views",
 * 2026-10-28). Without such an amenity the list shows its own choice, without the amenities whose
 * flag is `false` or "false". The order is a number or a numeric string, lowest first. An amenity
 * without an order comes after the ordered ones, in the list's own order.
 */
internal class AmenityListing private constructor(
    /** The order of each amenity that has one, by amenity id. */
    private val order: Map<String, Double>,
    /** The amenities whose flag is false, by id. */
    private val excluded: Set<String>,
    /** The amenities whose flag is true, by id. */
    private val included: Set<String>,
    /** The same by lower-case amenity title, for the legend's lifts and stairs. */
    private val orderByTitle: Map<String, Double>,
    private val excludedTitles: Set<String>,
    private val includedTitles: Set<String>,
) {
    /** An amenity has the flag true: the list shows only those. */
    val isLimitedToIncluded: Boolean get() = included.isNotEmpty()

    /**
     * The sort key of [amenityId]: its order, after every order without one. `null` when the list
     * does not show it: its flag is false, or other amenities have the flag true and it has not.
     */
    fun rank(amenityId: String?): Double? {
        if (amenityId == null) return if (isLimitedToIncluded) null else Double.MAX_VALUE
        if (amenityId in excluded || (isLimitedToIncluded && amenityId !in included)) return null
        return order[amenityId] ?: Double.MAX_VALUE
    }

    /** The rank of the amenity titled [title], as [rank]. */
    fun rankOfTitle(title: String): Double? {
        val key = title.lowercase()
        if (key in excludedTitles || (isLimitedToIncluded && key !in includedTitles)) return null
        return orderByTitle[key] ?: Double.MAX_VALUE
    }

    /**
     * The shortcut kinds of [places]: those of the amenities with the flag true when there are any,
     * else the kinds of [AmenityKind.SHORTCUTS] without those whose flag is false; by the data's
     * order, then in the order of [AmenityKind.entries]. Pure; covered by tests.
     */
    fun shortcuts(places: List<VenuePlace>): List<AmenityKind> = ordered(rankedKinds(places)).map { it.first }

    /**
     * The search's shortcuts: the kinds of [shortcuts], and, when an amenity has the flag true, each
     * other amenity with the flag true and a place, with its title in [language] and its icon from
     * [icons] (by amenity id). By the data's order, then kinds before the others. Pure; covered by
     * tests.
     */
    fun searchShortcuts(
        places: List<VenuePlace>,
        amenities: List<ProximiioAmenity>,
        icons: Map<String, ImageBitmap> = emptyMap(),
        language: String,
    ): List<AmenityShortcut> {
        val ranked: MutableList<Pair<AmenityShortcut, Double>> = rankedKinds(places).mapTo(mutableListOf()) { (kind, rank) -> AmenityShortcut.Kind(kind) to rank }
        if (isLimitedToIncluded) {
            val kinds = AmenityKind.kinds(amenities)
            val placed = places.mapNotNull { it.amenityId }.toSet()
            for (amenity in amenities) {
                if (amenity.id in kinds || amenity.id !in placed) continue
                val rank = rank(amenity.id) ?: continue
                val title = amenity.title(language) ?: amenity.title ?: amenity.id
                ranked += AmenityShortcut.Amenity(amenity.id, title, icons[amenity.id]) to rank
            }
        }
        return ordered(ranked).map { it.first }
    }

    private fun rankedKinds(places: List<VenuePlace>): List<Pair<AmenityKind, Double>> =
        AmenityKind.entries.mapNotNull { kind ->
            val matching = places.filter(kind::matches)
            if (matching.isEmpty()) return@mapNotNull null
            val isFlagged = matching.any { place -> place.amenityId?.let { it in included } ?: false }
            if (kind !in AmenityKind.SHORTCUTS && !isFlagged) return@mapNotNull null
            matching.mapNotNull { rank(it.amenityId) }.minOrNull()?.let { kind to it }
        }

    /** By rank, then in the given order. */
    private fun <T> ordered(ranked: List<Pair<T, Double>>): List<Pair<T, Double>> =
        ranked.withIndex().sortedWith(compareBy({ it.value.second }, { it.index })).map { it.value }

    override fun equals(other: Any?): Boolean =
        other is AmenityListing && order == other.order && excluded == other.excluded && included == other.included &&
            orderByTitle == other.orderByTitle && excludedTitles == other.excludedTitles && includedTitles == other.includedTitles

    override fun hashCode(): Int = listOf(order, excluded, included, orderByTitle, excludedTitles, includedTitles).hashCode()

    companion object {
        /** No amenity has the keys: each list keeps its own choice and order. */
        val ALL: AmenityListing = AmenityListing(emptyMap(), emptySet(), emptySet(), emptyMap(), emptySet(), emptySet())

        fun search(amenities: List<ProximiioAmenity>): AmenityListing = of(amenities, flag = "in_search", orderKey = "search_order")

        fun facilities(amenities: List<ProximiioAmenity>): AmenityListing = of(amenities, flag = "in_facilities", orderKey = "facilities_order")

        /** The listing of [amenities] under [flag] and [orderKey]. Pure; covered by tests. */
        fun of(amenities: List<ProximiioAmenity>, flag: String, orderKey: String): AmenityListing {
            val order = HashMap<String, Double>()
            val excluded = HashSet<String>()
            val included = HashSet<String>()
            val orderByTitle = HashMap<String, Double>()
            val excludedTitles = HashSet<String>()
            val includedTitles = HashSet<String>()
            for (amenity in amenities) {
                val metadata = (amenity.metadata as? JsonValue.Object)?.objectValue ?: continue
                val title = amenity.title?.lowercase()
                when (flag(metadata[flag])) {
                    false -> {
                        excluded += amenity.id
                        if (title != null) excludedTitles += title
                        continue
                    }
                    true -> {
                        included += amenity.id
                        if (title != null) includedTitles += title
                    }
                    null -> Unit
                }
                number(metadata[orderKey])?.let { rank ->
                    order[amenity.id] = rank
                    if (title != null) orderByTitle[title] = rank
                }
            }
            return AmenityListing(order, excluded, included, orderByTitle, excludedTitles, includedTitles)
        }

        private fun flag(value: JsonValue?): Boolean? = when (value) {
            is JsonValue.Bool -> value.boolValue
            is JsonValue.String -> when (value.stringValue?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            is JsonValue.Number -> value.doubleValue?.let { it != 0.0 }
            else -> null
        }

        private fun number(value: JsonValue?): Double? = when (value) {
            is JsonValue.Number -> value.doubleValue
            is JsonValue.String -> value.stringValue?.toDoubleOrNull()
            else -> null
        }
    }
}
