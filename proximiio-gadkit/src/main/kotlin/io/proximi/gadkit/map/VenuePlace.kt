package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import java.net.URI
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * A point of interest the visitor can open and route to. Built from the SDK's cached
 * features. Same rules as proximiio-blueiot-minimal-ios `VenuePOI`. Title and description are
 * read from `title_i18n` and `description_i18n` for the app's language when the feature has them.
 */
public data class VenuePlace(
    val id: String,
    val title: String,
    /** Feature `description`, if set in Proximi.io Portal. */
    val details: String? = null,
    val coordinate: Coordinate,
    /** Floor level. The route request takes it as the destination level. */
    val level: Double,
    /** Amenity id, `<category>:<amenity>`, or `null`. */
    val amenityId: String? = null,
    /** First entry of the feature's `images` property, if it is an absolute URL. */
    val imageUrl: String? = null,
    /**
     * The customer's id (`remote_id`): an artwork's collection id such as "GAD.2010.00033". The
     * artwork's geofence holds the same value.
     */
    val remoteId: String? = null,
    /**
     * True for an artwork: the artwork amenity ([ARTWORK_AMENITY_ID]), an `artwork_id` in `metadata`,
     * or a collection id ("GAD.2018.00034") in [remoteId]. Test artworks have no `artwork_id`.
     * Gallery and amenity POIs have none of the three.
     */
    val isArtwork: Boolean = false,
    /** `metadata.artist_name`: a string, or an i18n object like `title_i18n`. The journey list shows it under the title. */
    val artist: String? = null,
    /** `metadata.has_ar`: the journey list shows "AR". */
    val hasAR: Boolean = false,
    /** `metadata.has_audio`: the journey list shows a note. */
    val hasAudio: Boolean = false,
    /**
     * `metadata.gallery_id`: the gallery of an artwork. The gallery's POI holds the same value in
     * `remote_id` ([Gallery.names]).
     */
    val galleryId: String? = null,
) {
    public companion object {
        /**
         * The place of a point POI, or `null` for every feature that is not one.
         * [language] is the app's language tag, e.g. `ar` or `en-GB`.
         */
        internal fun from(feature: VenueFeature, language: String?): VenuePlace? {
            if (feature.type != "poi") return null
            val geometry = feature.geometry ?: return null
            if (geometry.type != "Point") return null
            val pair = geometry.coordinates.jsonArray() ?: return null
            if (pair.size < 2) return null
            // GeoJSON order is [longitude, latitude].
            val longitude = pair[0].jsonDouble() ?: return null
            val latitude = pair[1].jsonDouble() ?: return null
            if (!longitude.isFinite() || !latitude.isFinite()) return null

            val properties = feature.properties
            val metadata = properties?.get("metadata") as? Map<*, *>
            val image = (properties?.get("images") as? List<*>)?.firstOrNull()?.let(::text)
                ?.takeIf { runCatching { URI(it).scheme != null }.getOrDefault(false) }
            return VenuePlace(
                id = feature.id,
                title = localized(properties?.get("title_i18n"), language)
                    ?: text(properties?.get("title"))
                    ?: text(properties?.get("name"))
                    ?: feature.id,
                details = localized(properties?.get("description_i18n"), language) ?: text(properties?.get("description")),
                coordinate = Coordinate(latitude, longitude),
                level = feature.level ?: 0.0,
                amenityId = text(properties?.get("amenity")),
                imageUrl = image,
                remoteId = text(properties?.get("remote_id")),
                isArtwork = isArtwork(text(properties?.get("amenity")), properties?.get("metadata"), text(properties?.get("remote_id"))),
                artist = metadata?.let { localized(it["artist_name"], language) ?: text(it["artist_name"]) },
                hasAR = flag(metadata?.get("has_ar")),
                hasAudio = flag(metadata?.get("has_audio")),
                galleryId = text(metadata?.get("gallery_id")),
            )
        }

        /** Every point POI in [features], sorted by title (numbers in numeric order, case-insensitive). */
        internal fun all(features: List<VenueFeature>, language: String?): List<VenuePlace> =
            features.mapNotNull { from(it, language) }.sortedWith(compareBy(StandardTitleOrder) { it.title })

        /**
         * The place under a map tap. [identifiers] are the tapped feature ids, topmost first.
         * The first matching id is used.
         */
        public fun placeUnder(identifiers: List<String>, places: List<VenuePlace>): VenuePlace? {
            for (identifier in identifiers) {
                places.firstOrNull { it.id == identifier }?.let { return it }
            }
            return null
        }

        /** Case- and diacritic-insensitive substring match on the title. An empty query matches all. */
        public fun matching(query: String, places: List<VenuePlace>): List<VenuePlace> {
            val needle = query.trim()
            if (needle.isEmpty()) return places
            val folded = fold(needle)
            return places.filter { fold(it.title).contains(folded) }
        }

        /**
         * The value for [language] from an i18n object such as `{"en": "Café", "ar": "المقهى"}`,
         * falling back to the base language. `null` when the object has no non-empty value for it.
         */
        public fun localized(value: Any?, language: String?): String? {
            val translations = value as? Map<*, *> ?: return null
            if (language == null) return null
            val base = language.substringBefore('-')
            return text(translations[language]) ?: text(translations[base])
        }

        /** The nearest place on [level] (any level when `null`), by straight-line distance. iOS `TrackingMonitor.nearest`. */
        public fun nearest(coordinate: Coordinate?, level: Double?, places: List<VenuePlace>): VenuePlace? {
            if (coordinate == null) return null
            return places
                .filter { VenueLevel.matches(it.level, level) }
                .minByOrNull { it.coordinate.squaredDegrees(coordinate) }
        }

        /** Mean coordinate of the venue's places. iOS `ArrivalDetector.centre(of:)`. */
        public fun centre(places: List<VenuePlace>): Coordinate? {
            if (places.isEmpty()) return null
            return Coordinate(places.sumOf { it.coordinate.latitude } / places.size, places.sumOf { it.coordinate.longitude } / places.size)
        }

        private val COLLECTION_ID = Regex("""GAD\.\d{4}\.\d+""")

        /** The amenity of artwork POIs in the GAD organisation (PM, Monday 2026-10-12). */
        public const val ARTWORK_AMENITY_ID: String = "7405e6e6-d9f6-4146-adda-de6adbf4a8a1:f2d78fac-23be-4f24-b5aa-571809e9e99d"

        /** See [VenuePlace.isArtwork]. */
        public fun isArtwork(amenityId: String?, metadata: Any?, remoteId: String?): Boolean {
            if (amenityId == ARTWORK_AMENITY_ID) return true
            if (remoteId != null && COLLECTION_ID.matches(remoteId)) return true
            return (metadata as? Map<*, *>)?.get("artwork_id")?.let { it !is String || it.isNotEmpty() } == true
        }

        private fun text(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }

        /** A metadata flag: `true`, `"true"` in any case, or a non-zero number. Pure; covered by tests. */
        internal fun flag(value: Any?): Boolean = when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toDouble() != 0.0
            else -> false
        }

        private fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    }
}

/**
 * Approximates Foundation's `localizedStandardCompare`: case-insensitive, digit runs compared as numbers.
 */
public object StandardTitleOrder : Comparator<String> {
    private val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val left = chunk.findAll(a).map { it.value }.toList()
        val right = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val result = if (x[0].isDigit() && y[0].isDigit()) {
                val xs = x.trimStart('0')
                val ys = y.trimStart('0')
                if (xs.length != ys.length) xs.length - ys.length else xs.compareTo(ys)
            } else {
                collator.compare(x, y)
            }
            if (result != 0) return result
        }
        return left.size - right.size
    }
}
