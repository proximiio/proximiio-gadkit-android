package io.proximi.gadkit.map

import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.StopState

/** A gallery drawn filled: with the level colour when [isDestination], with its tint when visited. */
internal data class GalleryFill(val gallery: Gallery, val isDestination: Boolean)

/**
 * What the journey map draws for a journey besides the map library's stop markers: the gallery
 * fills. Port of iOS `VisitView.refreshMapLayers`; the app draws the result (`JourneyMapLayers`).
 */
internal object JourneyMapContent {
    /**
     * The gallery of the active stop as the destination, and as visited every gallery in
     * [visitedGalleryIds] (the visitor has been inside) or holding a done stop. Other galleries are
     * not filled.
     */
    internal fun fills(
        journey: Journey,
        activeStopId: String?,
        galleries: List<Gallery>,
        visitedGalleryIds: Set<String> = emptySet(),
    ): List<GalleryFill> {
        val active = journey.stops.firstOrNull { it.id == activeStopId }
        val destination = active?.let { Gallery.containing(it.coordinate, it.level, galleries) }
        val visitedByStops = journey.stops
            .filter { it.state == StopState.DONE }
            .mapNotNull { Gallery.containing(it.coordinate, it.level, galleries)?.id }
        val visited = visitedGalleryIds + visitedByStops
        return galleries.mapNotNull { gallery ->
            when {
                gallery.id == destination?.id -> GalleryFill(gallery, isDestination = true)
                gallery.id in visited -> GalleryFill(gallery, isDestination = false)
                else -> null
            }
        }
    }

    /** The fills shown on [level]. */
    internal fun fillsOn(level: Double, fills: List<GalleryFill>): List<GalleryFill> =
        fills.filter { VenueLevel.same(it.gallery.level, level) }
}

/** The language the map draws its labels and floor names in. */
internal object MapLanguage {
    /** `ar` for an Arabic app language, otherwise `en`: the two languages the app ships. */
    internal fun forAppLanguage(language: String?): String =
        if (language?.substringBefore('-')?.lowercase() == "ar") "ar" else "en"
}
