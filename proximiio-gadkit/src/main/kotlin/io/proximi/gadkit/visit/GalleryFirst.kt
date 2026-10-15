package io.proximi.gadkit.visit

import io.proximi.gadkit.map.Gallery

/**
 * Gallery first on the stop card (DAILY-CALL R2), as `galleryAhead` and `markGalleryReached` of the
 * `VisitCard` in iOS `VisitView`. While the visitor is outside the gallery of the active stop, the card
 * names the gallery and shows no stop number. The first fix inside that gallery latches the stop: the
 * card names the artwork until the active stop changes, so it does not flicker at the gallery's edge.
 * The route always leads to the artwork. The stop's gallery and the visitor's gallery come from the
 * gallery polygons ([Gallery.containing]). Pure; covered by tests.
 */
internal object GalleryFirst {
    /** The gallery the card names instead of the artwork, or `null` once the stop is latched. */
    internal fun galleryAhead(activeStopId: String?, stopGallery: Gallery?, reachedStopId: String?): Gallery? =
        if (activeStopId == null || reachedStopId == activeStopId) null else stopGallery

    /**
     * The latched stop after a fix or a change of the active stop: the active stop once the visitor
     * is in its gallery, else [reachedStopId] unchanged.
     */
    internal fun reachedStopId(activeStopId: String?, stopGallery: Gallery?, visitorGallery: Gallery?, reachedStopId: String?): String? {
        if (activeStopId == null || reachedStopId == activeStopId) return reachedStopId
        if (stopGallery == null || visitorGallery?.id != stopGallery.id) return reachedStopId
        return activeStopId
    }
}
