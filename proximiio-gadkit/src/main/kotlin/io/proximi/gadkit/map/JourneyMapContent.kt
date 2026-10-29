package io.proximi.gadkit.map

/**
 * A gallery drawn filled: the destination with the level colour, a visited gallery with its tint, every
 * other gallery in the venue style's gallery colour over its artworks and furniture (iOS 1.24.0).
 */
internal data class GalleryFill(val gallery: Gallery, val style: Style) {
    enum class Style(val value: String) {
        /** The venue style's gallery colour, over the artworks and furniture. */
        STANDARD("standard"),
        VISITED("visited"),
        DESTINATION("destination"),
    }
}

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
