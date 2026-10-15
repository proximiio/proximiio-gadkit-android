package io.proximi.gadkit.visit

import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.UiText

/**
 * Text of the stop card at the bottom of the journey map (Figma "Stop Info"), as the `VisitCard`
 * of iOS `VisitView`. Keys are string resources; the English is noted on each. Pure; covered by tests.
 */
internal object VisitCardText {
    /**
     * The caption: `visit_next_stop_is` "NEXT STOP IS %s", `visit_last_stop` "LAST STOP",
     * `visit_gallery_x` "GALLERY · %s", `visit_next_stop` "NEXT STOP", `visit_current_artwork`
     * "CURRENT ARTWORK", `visit_journey_complete_caps` "JOURNEY COMPLETE".
     *
     * @param isLastOpenStop the active stop is the last one still to visit (04.01).
     * @param gallery the gallery the visitor is in.
     * @param galleryAhead the stop's gallery the card names instead of the artwork ([GalleryFirst]).
     */
    internal fun caption(state: VisitCardState, isLastOpenStop: Boolean, gallery: String?, galleryAhead: String? = null): UiText =
        when (state) {
            is VisitCardState.NextStopOnOtherLevel -> UiText.Resource("visit_next_stop_is", listOf(galleryAhead ?: state.stop))
            VisitCardState.Starting, is VisitCardState.Walking -> when {
                galleryAhead != null -> UiText.Resource("visit_next_stop")
                isLastOpenStop -> UiText.Resource("visit_last_stop")
                gallery != null -> UiText.Resource("visit_gallery_x", listOf(gallery))
                else -> UiText.Resource("visit_next_stop")
            }
            is VisitCardState.AtStop -> UiText.Resource("visit_current_artwork")
            VisitCardState.Finished -> UiText.Resource("visit_journey_complete_caps")
        }

    /**
     * The title: the stop, `visit_your_journey` "Your journey", the route's next level change
     * ([GuidanceText.levelChangeTitle], else `visit_go_to_level` "Go to level %d"),
     * `visit_walked_your_route` "You've walked your route". While walking, [galleryAhead] replaces
     * the stop.
     */
    internal fun title(state: VisitCardState, galleryAhead: String? = null, levelChangeTitle: UiText? = null): UiText = when (state) {
        VisitCardState.Starting -> UiText.Resource("visit_your_journey")
        is VisitCardState.Walking -> UiText.Verbatim(galleryAhead ?: state.stop)
        is VisitCardState.AtStop -> UiText.Verbatim(state.stop)
        is VisitCardState.NextStopOnOtherLevel -> levelChangeTitle ?: UiText.Resource("visit_go_to_level", listOf(state.level))
        VisitCardState.Finished -> UiText.Resource("visit_walked_your_route")
    }

    /**
     * The guidance instruction under the title. While the next stop is on another floor the
     * instruction is left out when it repeats the title: at the level change both say "Take the
     * stairs to level 3".
     */
    internal fun instruction(state: VisitCardState, guidance: UiText?, title: UiText): UiText? = when (state) {
        is VisitCardState.Walking -> guidance
        is VisitCardState.NextStopOnOtherLevel -> guidance?.takeUnless { it == title }
        else -> null
    }

    /** Whether the icon shows the stop number: inside the stop's gallery only (R2), and always at the stop. */
    internal fun showsStopNumber(state: VisitCardState, galleryAhead: String?): Boolean =
        state is VisitCardState.AtStop || galleryAhead == null

    /** Whether the card shows the route guidance (instruction, distance and time). */
    internal fun showsGuidance(state: VisitCardState): Boolean =
        state is VisitCardState.Walking || state is VisitCardState.NextStopOnOtherLevel

}
