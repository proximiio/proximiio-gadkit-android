package io.proximi.gadkit.map

import io.proximi.gadkit.VenueService
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.Geometry
import io.proximi.gadkit.map.VenueFeature
import io.proximi.gadkit.map.VenueGeofence
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.ArtworkArea
import io.proximi.gadkit.visit.LiftArea
import io.proximi.sdk.core.model.JsonValue
import io.proximi.sdk.core.model.ProximiioFeature
import io.proximi.sdk.core.model.ProximiioGeofence
import io.proximi.sdk.features
import io.proximi.sdk.floors
import io.proximi.sdk.geofences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The SDK's feature as core reads it: `properties.type` as the type, JSON decoded to lists, maps and numbers. */
internal fun ProximiioFeature.toVenueFeature(): VenueFeature = VenueFeature(
    id = id,
    type = propertyType,
    geometry = geometry?.let { Geometry(it.type, it.coordinates?.decoded()) },
    properties = properties?.objectValue?.mapValues { it.value.decoded() },
    level = level,
)

private fun JsonValue.decoded(): Any? = when (this) {
    is JsonValue.Object -> objectValue?.mapValues { it.value.decoded() }
    is JsonValue.Array -> arrayValue?.map { it.decoded() }
    is JsonValue.Number -> doubleValue
    is JsonValue.String -> stringValue
    is JsonValue.Bool -> boolValue
    else -> null
}

/** Every point POI of the cached venue data, for [language] (an app language tag such as `ar`). */
public suspend fun VenueService.places(language: String?): List<VenuePlace> = withContext(Dispatchers.Default) {
    VenuePlace.all(sdk.features().map { it.toVenueFeature() }, language)
}

/**
 * The venue's galleries, as iOS `VisitView` loads them: the `gallery` polygon features named
 * after the gallery place inside them, else the named gallery geofences.
 */
public suspend fun VenueService.galleries(language: String?): List<Gallery> {
    val outlines = withContext(Dispatchers.Default) {
        val features = sdk.features().map { it.toVenueFeature() }
        Gallery.all(features, VenuePlace.all(features, language))
    }
    if (outlines.isNotEmpty()) return outlines
    // For a repeated id, the first floor is used, as on iOS.
    val levels = buildMap { sdk.floors().forEach { putIfAbsent(it.id, it.level) } }
    val geofences = sdk.geofences().filter { it.isGallery }.map { VenueGeofence(it.id, it.name, it.polygon, it.floorId) }
    return Gallery.all(geofences, levels)
}

/**
 * `metadata.geofence_type` is `gallery` or `artwork`, as iOS `Gallery.swift`. Artwork geofences
 * (circles) lie inside the gallery geofences.
 */
internal val ProximiioGeofence.isGallery: Boolean get() = metadata?.get("geofence_type")?.stringValue == "gallery"

/** An artwork geofence: `remote_id` is the artwork's collection id, as on the artwork POI. */
internal val ProximiioGeofence.isArtwork: Boolean get() = metadata?.get("geofence_type")?.stringValue == "artwork"

/** The artwork geofence shapes, by `remote_id`. For a repeated id, the first geofence is used, as on iOS. */
internal suspend fun VenueService.artworkAreas(): Map<String, ArtworkArea> = buildMap {
    for (geofence in sdk.geofences()) {
        val remoteId = geofence.remoteId ?: continue
        if (!geofence.isArtwork) continue
        val center = geofence.area?.let { Coordinate(it.latitude, it.longitude) }
        putIfAbsent(remoteId, ArtworkArea(center, geofence.radius, geofence.polygon))
    }
}

/** The venue's lift cabin outlines (`elevator` polygon features), as iOS `LiftArea.all(features:)`. */
internal suspend fun VenueService.lifts(): List<LiftArea> = withContext(Dispatchers.Default) {
    LiftArea.all(sdk.features().map { it.toVenueFeature() })
}
