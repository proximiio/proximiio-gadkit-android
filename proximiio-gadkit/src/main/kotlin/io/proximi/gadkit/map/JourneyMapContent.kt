package io.proximi.gadkit.map

/** A gallery drawn filled: with the level colour when [isDestination], with its tint when visited. */
internal data class GalleryFill(val gallery: Gallery, val isDestination: Boolean)

/** The journey map's gallery fills on a floor. `VisitViewModel` chooses them, as iOS `VisitView.refreshMapLayers`. */
internal object JourneyMapContent {
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
