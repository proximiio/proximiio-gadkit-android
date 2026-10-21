package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.roundedInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * A gallery has a name, a level and an outline. Galleries come from the venue's polygon
 * features of type `gallery` (also `gallery-visited` and `gallery-destination`), named after the
 * gallery POI inside them, and from polygon geofences. Journey stops and the visitor's position
 * are placed in a gallery by a point-in-polygon test on the same level.
 */
/**
 * A gallery that a personalised journey leads to before the artworks in it (PM, Monday "Navigation to
 * a gallery", 2026-10-15), as iOS `GalleryDestination`. Two records share the id that an artwork
 * names in `metadata.gallery_id`: the gallery geofence (`metadata.geofence_type` gallery) has it in
 * `remote_id`, and so does the gallery's POI. The geofence gives the outline; the POI gives the name
 * and the end of the route.
 *
 * @property gallery `id` is the `gallery_id`, `name` the POI's title, such as "G303 Language", `level`
 *   the POI's, `ring` the geofence's outline.
 * @property entry the gallery's POI: where the route to the gallery ends.
 * @property placeId the id of the gallery's POI.
 */
internal data class GalleryDestination(val gallery: Gallery, val entry: Coordinate, val placeId: String) {
    val id: String get() = gallery.id

    /** The text of the map marker: the first word of the name, such as "G303". */
    val label: String get() = gallery.name.split(' ').firstOrNull { it.isNotEmpty() } ?: gallery.name
}

internal data class Gallery(
    internal val id: String,
    internal val name: String,
    internal val level: Double,
    /** Outline as (latitude, longitude) pairs. */
    internal val ring: List<Coordinate>,
) {
    internal fun contains(coordinate: Coordinate, level: Double?): Boolean {
        if (level != null && !VenueLevel.same(level, this.level)) return false
        return ringContains(ring, coordinate)
    }

    /**
     * Metres from [coordinate] to the outline, 0 inside it. Measured on a flat projection around
     * [coordinate], which is exact enough within a building.
     */
    internal fun distanceTo(coordinate: Coordinate): Double {
        if (contains(coordinate, null)) return 0.0
        val metersPerLatitude = 111_320.0
        val metersPerLongitude = metersPerLatitude * cos(coordinate.latitude * PI / 180)
        fun x(point: Coordinate) = (point.longitude - coordinate.longitude) * metersPerLongitude
        fun y(point: Coordinate) = (point.latitude - coordinate.latitude) * metersPerLatitude
        var nearest = Double.POSITIVE_INFINITY
        var j = ring.size - 1
        for (i in ring.indices) {
            val ax = x(ring[j])
            val ay = y(ring[j])
            val dx = x(ring[i]) - ax
            val dy = y(ring[i]) - ay
            val lengthSquared = dx * dx + dy * dy
            // The point of the edge nearest the origin, [coordinate].
            val t = if (lengthSquared > 0) (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
            nearest = min(nearest, hypot(ax + t * dx, ay + t * dy))
            j = i
        }
        return nearest
    }

    /** Fill colours of a level as ARGB, opaque. */
    internal data class Colors(val destination: Long, val visited: Long)

    internal companion object {
        /**
         * Polygon geofences with a name and a known floor. [levels] maps floor ids to levels.
         * Geofence polygons are GeoJSON `[longitude, latitude]` pairs.
         */
        internal fun all(geofences: List<VenueGeofence>, levels: Map<String, Double>): List<Gallery> =
            geofences.mapNotNull { geofence ->
                val name = geofence.name?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val polygon = geofence.polygon?.takeIf { it.size >= 3 } ?: return@mapNotNull null
                val level = geofence.floorId?.let { levels[it] } ?: return@mapNotNull null
                val ring = polygon.mapNotNull { pair -> if (pair.size >= 2) Coordinate(pair[1], pair[0]) else null }
                if (ring.size >= 3) Gallery(geofence.id, name, level, ring) else null
            }

        /**
         * Polygon features whose type starts with `gallery`. The name is the title of a place
         * inside the outline whose title names a gallery; outlines without one are left out.
         */
        internal fun all(features: List<VenueFeature>, places: List<VenuePlace>): List<Gallery> {
            val galleryPlaces = places.filter { it.title.contains("gallery", ignoreCase = true) }
            return features.mapNotNull { feature ->
                if (!(feature.type ?: "").startsWith("gallery")) return@mapNotNull null
                val geometry = feature.geometry ?: return@mapNotNull null
                val level = feature.level ?: return@mapNotNull null
                val ring = outerRing(geometry.type, geometry.coordinates) ?: return@mapNotNull null
                val outline = Gallery(feature.id, "", level, ring)
                val place = galleryPlaces.firstOrNull { outline.contains(it.coordinate, it.level) } ?: return@mapNotNull null
                Gallery(feature.id, place.title, level, ring)
            }
        }

        /** The outer ring of a GeoJSON Polygon, or of the first polygon of a MultiPolygon. */
        internal fun outerRing(type: String, coordinates: Any?): List<Coordinate>? {
            val rings = when (type) {
                "Polygon" -> coordinates.jsonArray()?.firstOrNull()
                "MultiPolygon" -> coordinates.jsonArray()?.firstOrNull().jsonArray()?.firstOrNull()
                else -> null
            }
            val ring = rings.jsonArray()?.mapNotNull { element ->
                val pair = element.jsonArray()?.takeIf { it.size >= 2 } ?: return@mapNotNull null
                val longitude = pair[0].jsonDouble() ?: return@mapNotNull null
                val latitude = pair[1].jsonDouble() ?: return@mapNotNull null
                Coordinate(latitude, longitude)
            }
            return ring?.takeIf { it.size >= 3 }
        }

        /**
         * The galleries a personalised journey leads to first, by `gallery_id` ([GalleryDestination]).
         * A gallery without a polygon geofence or a POI is left out. Geofence polygons are GeoJSON
         * `[longitude, latitude]` pairs. Pure; covered by tests.
         */
        internal fun destinations(geofences: List<VenueGeofence>, places: List<VenuePlace>): Map<String, GalleryDestination> {
            val pois = LinkedHashMap<String, VenuePlace>()
            for (place in places) {
                if (place.isArtwork) continue
                val id = place.remoteId?.takeIf { it.isNotEmpty() } ?: continue
                pois.putIfAbsent(id, place)
            }
            val destinations = LinkedHashMap<String, GalleryDestination>()
            for (geofence in geofences) {
                if (geofence.geofenceType != "gallery") continue
                val id = geofence.remoteId?.takeIf { it.isNotEmpty() } ?: continue
                if (id in destinations) continue
                val poi = pois[id] ?: continue
                val polygon = geofence.polygon ?: continue
                val ring = polygon.mapNotNull { pair -> if (pair.size >= 2) Coordinate(pair[1], pair[0]) else null }
                if (ring.size < 3) continue
                destinations[id] = GalleryDestination(Gallery(id, poi.title, poi.level, ring), poi.coordinate, poi.id)
            }
            return destinations
        }

        /**
         * Gallery names by the gallery's id: the title of the POI whose `remote_id` is the id that an
         * artwork names in `metadata.gallery_id` ([VenuePlace.galleryId]), such as "G403 Weaving"
         * (PM 2026-10-15). Artworks are left out: their `remote_id` is a collection id. Pure;
         * covered by tests.
         */
        internal fun names(places: List<VenuePlace>): Map<String, String> = buildMap {
            for (place in places) {
                if (place.isArtwork) continue
                val id = place.remoteId?.takeIf { it.isNotEmpty() } ?: continue
                val name = place.title.trim().takeIf { it.isNotEmpty() } ?: continue
                putIfAbsent(id, name)
            }
        }

        internal fun containing(coordinate: Coordinate, level: Double?, galleries: List<Gallery>): Gallery? =
            galleries.firstOrNull { it.contains(coordinate, level) }

        /** Fill colours per level (page 3, section `Colors`, node 10930:60509). */
        internal fun colors(level: Double): Colors = when (level.roundedInt()) {
            0 -> Colors(0xFF00FFB2, 0xFF80FFD9)
            1 -> Colors(0xFF6FA6FF, 0xFFB7D3FF)
            2 -> Colors(0xFFBB87F3, 0xFFDDC3F9)
            3 -> Colors(0xFFFFE523, 0xFFFFF291)
            else -> Colors(0xFFFA8352, 0xFFFDC1A8)
        }
    }
}

/** Ray casting on the longitude axis. [ring] is (latitude, longitude) pairs. */
internal fun ringContains(ring: List<Coordinate>, coordinate: Coordinate): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.latitude > coordinate.latitude) != (b.latitude > coordinate.latitude)) {
            val x = (b.longitude - a.longitude) * (coordinate.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
            if (coordinate.longitude < x) inside = !inside
        }
        j = i
    }
    return inside
}
