package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.GadSettings
import io.proximi.gadkit.map.GalleryDestination
import io.proximi.gadkit.map.VenueLevel
import io.proximi.gadkit.map.VenuePlace
import io.proximi.map.core.JourneyStop
import io.proximi.map.core.VenuePosition

/**
 * Detects when the gallery of the journey's active stop is nearby, as iOS `GalleryNearbyDetector`:
 * the visitor is less than [nearbyMeters] from the gallery's POI in a straight line, on its level, or
 * inside the gallery's outline. `VisitViewModel` reports it as [VisitEvent.GalleryNearby]; the host app
 * can start the gallery's introduction on it (Nagarro, PM, Monday "Approaching gallery information",
 * 2026-11-10).
 *
 * A gallery is reported once while it stays the gallery of the active stop: two stops in a row in one
 * gallery report it once, and a journey that comes back to a gallery reports it again. [reset] reports
 * the current gallery again: the journey resumed after a pause (PM, Monday "Sending entered geofences
 * & approaching gallery at start", 2026-11-10).
 */
internal class GalleryNearbyDetector(private val nearbyMeters: Double = GadSettings.STANDARD.galleryNearbyMeters) {
    /** The `gallery_id` of the last gallery reported, while it is still the active stop's gallery. */
    private var reportedGalleryId: String? = null

    /** The next [update] reports the active stop's gallery again when it is nearby. */
    fun reset() {
        reportedGalleryId = null
    }

    /**
     * [stop] when its gallery is nearby and was not reported yet, else `null`. [outlines] are the
     * gallery outlines ([GalleryDestination.id] is the `gallery_id`).
     */
    fun update(position: VenuePosition?, stop: JourneyStop?, places: List<VenuePlace>, outlines: List<GalleryDestination>): JourneyStop? {
        val gallery = stop?.let { gallery(it.poiId ?: it.id, places) }
        val galleryId = gallery?.remoteId
        if (stop == null || gallery == null || galleryId == null) {
            // A stop without a gallery ends the last report: the next stop in that gallery reports it again.
            if (stop != null) reportedGalleryId = null
            return null
        }
        if (galleryId == reportedGalleryId) return null
        // Another gallery is ahead now; the earlier one reports again when the journey comes back.
        reportedGalleryId = null
        if (position == null) return null
        val here = Coordinate(position.coordinate.latitude, position.coordinate.longitude)
        val level = position.floor?.level
        val isInside = outlines.firstOrNull { it.id == galleryId }?.gallery?.contains(here, level) ?: false
        if (!isInside && !isNearby(here, level, gallery.coordinate, gallery.level, nearbyMeters)) return null
        reportedGalleryId = galleryId
        FieldLog.log("gallery nearby: ${gallery.title} · stop ${stop.title}${if (isInside) " · inside" else ""}")
        return stop
    }

    companion object {
        /**
         * The gallery POI of the artwork [placeId]: the place that is not an artwork and whose
         * `remote_id` is the artwork's `metadata.gallery_id`. Pure; covered by tests.
         */
        fun gallery(placeId: String, places: List<VenuePlace>): VenuePlace? {
            val galleryId = places.firstOrNull { it.id == placeId }?.galleryId?.takeIf { it.isNotEmpty() } ?: return null
            return places.firstOrNull { !it.isArtwork && it.remoteId == galleryId }
        }

        /**
         * `true` when the gallery's POI is on the visitor's level and less than [nearbyMeters] away
         * in a straight line. An unknown level matches any level. Pure; covered by tests.
         */
        fun isNearby(
            position: Coordinate,
            level: Double?,
            gallery: Coordinate,
            galleryLevel: Double?,
            nearbyMeters: Double = GadSettings.STANDARD.galleryNearbyMeters,
        ): Boolean = VenueLevel.matches(level, galleryLevel) && position.distanceTo(gallery) < nearbyMeters
    }
}
