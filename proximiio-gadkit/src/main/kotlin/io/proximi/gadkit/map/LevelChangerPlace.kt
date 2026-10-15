package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate

/**
 * A staircase, lift or escalator POI: a point feature of type `staircase`, `elevator` or
 * `escalator`, as iOS `LevelChangerPlace`. It serves its own floor and the floors in
 * `properties.levels`. The map library draws a POI on its own floor only; GADKit hides these POIs
 * and draws them on every floor they serve ([JourneyMapLayers]), as the web map does (Monday "All
 * level changers visible", 2026-10-15). In the GAD data the lifts are POIs on level 0 with `levels`
 * [1, 2, 3, 4].
 */
internal data class LevelChangerPlace(
    val id: String,
    val featureType: String,
    val coordinate: Coordinate,
    /** The POI's floor and the floors in `properties.levels`. */
    val levels: List<Double>,
) {
    fun serves(level: Double): Boolean = levels.any { VenueLevel.same(it, level) }

    internal companion object {
        val FEATURE_TYPES: List<String> = listOf("elevator", "staircase", "escalator")

        /** `null` for every feature that is not a point of a level changer type. */
        fun from(feature: VenueFeature): LevelChangerPlace? {
            val type = feature.type?.takeIf { it in FEATURE_TYPES } ?: return null
            val geometry = feature.geometry?.takeIf { it.type == "Point" } ?: return null
            val pair = geometry.coordinates.jsonArray()?.takeIf { it.size >= 2 } ?: return null
            // GeoJSON order is [longitude, latitude].
            val longitude = pair[0].jsonDouble() ?: return null
            val latitude = pair[1].jsonDouble() ?: return null
            val listed = feature.properties?.get("levels").jsonArray()?.mapNotNull { it.jsonDouble() }.orEmpty()
            return LevelChangerPlace(feature.id, type, Coordinate(latitude, longitude), listOfNotNull(feature.level) + listed)
        }

        /** Every level changer POI in [features]. Pure; covered by tests. */
        fun all(features: List<VenueFeature>): List<LevelChangerPlace> = features.mapNotNull(::from)
    }
}
