package io.proximi.gadkit.map

import kotlinx.coroutines.CancellationException
import io.proximi.sdk.amenities
import io.proximi.sdk.core.model.ProximiioAmenity
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.Geometry
import io.proximi.gadkit.map.VenueFeature
import io.proximi.gadkit.map.VenueGeofence
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.ArtworkArea
import io.proximi.gadkit.visit.LiftArea
import io.proximi.gadkit.visit.VisitController
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
internal suspend fun VenueService.places(language: String?): List<VenuePlace> {
    val amenities = catalogue()
    return withContext(Dispatchers.Default) {
        VenuePlace.withAmenityKinds(VenuePlace.all(sdk.features().map { it.toVenueFeature() }, language), amenities)
    }
}

/** The SDK's amenity catalogue, or none when it cannot be read. */
internal suspend fun VenueService.catalogue(): List<ProximiioAmenity> = try {
    sdk.amenities().distinctBy { it.id }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptyList()
}

/** The venue's features as core reads them. */
internal suspend fun VenueService.venueFeatures(): List<VenueFeature> = withContext(Dispatchers.Default) {
    sdk.features().map { it.toVenueFeature() }
}

/**
 * The galleries of gallery first ([Gallery.destinations]), each with its polygon feature when the
 * venue data has one.
 */
internal suspend fun VenueService.galleryDestinations(places: List<VenuePlace>): Map<String, GalleryDestination> =
    Gallery.destinations(venueGeofences(), places, venueFeatures())

/**
 * The venue's galleries, as iOS `Gallery.load`: the galleries with a `gallery_id` (named by their
 * POI, outlined by their polygon), then the gallery outlines among the features (or the named gallery
 * geofences when there are none) that hold none of their POIs. The outlines alone are named by a POI
 * whose title says "gallery"; the GAD POIs renamed to "G101 Pop Culture" on 2026-10-28 left only three
 * galleries (PM, Monday "Visited gallery visualization").
 */
internal suspend fun VenueService.galleries(language: String?): List<Gallery> {
    val features = venueFeatures()
    val places = withContext(Dispatchers.Default) { VenuePlace.all(features, language) }
    val destinations = Gallery.destinations(venueGeofences(), places, features).values.toList()
    var outlines = withContext(Dispatchers.Default) { Gallery.all(features, places) }
    if (outlines.isEmpty()) {
        // For a repeated id, the first floor is used, as on iOS.
        val levels = buildMap { sdk.floors().forEach { putIfAbsent(it.id, it.level) } }
        val geofences = sdk.geofences().filter { it.isGallery }.map { VenueGeofence(it.id, it.name, it.polygon, it.floorId) }
        outlines = Gallery.all(geofences, levels)
    }
    return Gallery.merged(destinations, outlines)
}

/**
 * `metadata.geofence_type` is `gallery` or `artwork`, as iOS `Gallery.swift`. Artwork geofences
 * (circles) lie inside the gallery geofences.
 */
internal val ProximiioGeofence.isGallery: Boolean get() = metadata?.get("geofence_type")?.stringValue == "gallery"

/** An artwork geofence: `remote_id` is the artwork's collection id, as on the artwork POI. */
internal val ProximiioGeofence.isArtwork: Boolean get() = metadata?.get("geofence_type")?.stringValue == "artwork"

/** The venue's geofences as core reads them. */
internal suspend fun VenueService.venueGeofences(): List<VenueGeofence> = sdk.geofences().map {
    VenueGeofence(
        it.id, it.name, it.polygon, it.floorId, it.remoteId, it.metadata?.get("geofence_type")?.stringValue,
        center = it.area?.let { area -> Coordinate(area.latitude, area.longitude) },
        radius = it.radius,
    )
}

/** The shapes of the artwork geofences, by `remote_id` ([VisitController.artworkAreas]). */
internal suspend fun VenueService.artworkAreas(): Map<String, List<ArtworkArea>> = VisitController.artworkAreas(venueGeofences())

/** The venue's lifts, staircases and escalators ([LevelChangerPlace]). */
internal suspend fun VenueService.levelChangers(): List<LevelChangerPlace> = withContext(Dispatchers.Default) {
    LevelChangerPlace.all(sdk.features().map { it.toVenueFeature() })
}

/** The venue's lift cabin outlines (`elevator` polygon features), as iOS `LiftArea.all(features:)`. */
internal suspend fun VenueService.lifts(): List<LiftArea> = withContext(Dispatchers.Default) {
    LiftArea.all(sdk.features().map { it.toVenueFeature() })
}
