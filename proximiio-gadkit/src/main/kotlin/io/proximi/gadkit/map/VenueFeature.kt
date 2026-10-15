package io.proximi.gadkit.map

/**
 * A GeoJSON geometry. [coordinates] holds nested lists of numbers as decoded from JSON,
 * e.g. `[lon, lat]` for a Point and `[[[lon, lat], …]]` for a Polygon.
 */
internal data class Geometry(val type: String, val coordinates: Any?)

/**
 * A venue feature as core reads it. The app maps the SDK's feature to this:
 * [type] is the feature's `properties.type`, [properties] its decoded JSON properties.
 */
internal data class VenueFeature(
    val id: String,
    val type: String?,
    val geometry: Geometry?,
    val properties: Map<String, Any?>?,
    val level: Double?,
)

/** A geofence as core reads it. [polygon] is GeoJSON `[longitude, latitude]` pairs. */
internal data class VenueGeofence(
    val id: String,
    val name: String?,
    val polygon: List<List<Double>>?,
    val floorId: String?,
    /** `remote_id`: the gallery's id, which an artwork names in `metadata.gallery_id`. */
    val remoteId: String? = null,
    /** `metadata.geofence_type`: `gallery` or `artwork`. */
    val geofenceType: String? = null,
)

/** A JSON number as a double, or `null`. */
internal fun Any?.jsonDouble(): Double? = (this as? Number)?.toDouble()

/** A JSON array, or `null`. */
internal fun Any?.jsonArray(): List<Any?>? = this as? List<*>
