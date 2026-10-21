package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate

/**
 * A staircase, lift or escalator POI: a point feature of type `staircase`, `elevator` or
 * `escalator`, as iOS `LevelChangerPlace`. The map library draws a POI on its own floor only;
 * GADKit hides these POIs and draws them on every floor they serve ([JourneyMapLayers]), as the web
 * map does (Monday "All level changers visible", 2026-10-15).
 *
 * The floors served follow the iOS SDK router (`LevelChangerExtractor.levelChangers(from:)`,
 * 6.0.0-beta.60): exactly the floors in `properties.levels`, without the POI's own floor. In the
 * GAD data lift 9f411e5a is a POI on level 0 with `levels` [1, 2, 3, 4]; that router does not use it
 * on level 0, so GADKit does not draw it there. Without usable `levels` the POI serves its own
 * floor. The Android SDK router takes the same rule in 6.0.0-beta.26.
 */
internal data class LevelChangerPlace(
    val id: String,
    val featureType: String,
    val coordinate: Coordinate,
    /** The floors in `properties.levels`, or the POI's own floor when `levels` is missing, empty or unreadable. */
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
            val listed = feature.properties?.get("levels")?.let(::levels)
            return LevelChangerPlace(feature.id, type, Coordinate(latitude, longitude), listed ?: listOfNotNull(feature.level))
        }

        /**
         * The finite levels in a `properties.levels` value, or `null` when it holds none. Entries may
         * be numbers or numeric strings; a single number or string counts as a one-entry list. Same
         * rule as the iOS SDK's `LevelChangerExtractor.levels(from:)`. Pure; covered by tests.
         */
        fun levels(value: Any): List<Double>? {
            val entries = value.jsonArray() ?: listOf(value)
            val levels = entries.mapNotNull { entry ->
                when (entry) {
                    is Number -> entry.toDouble()
                    is String -> entry.trim().toDoubleOrNull()
                    else -> null
                }?.takeIf { it.isFinite() }
            }
            return levels.ifEmpty { null }
        }

        /** Every level changer POI in [features]. Pure; covered by tests. */
        fun all(features: List<VenueFeature>): List<LevelChangerPlace> = features.mapNotNull(::from)
    }
}
