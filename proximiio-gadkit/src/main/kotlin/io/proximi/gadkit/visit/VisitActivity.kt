package io.proximi.gadkit.visit

import io.proximi.gadkit.UiText

/**
 * The content of the visit's ongoing notification, as iOS `VisitActivityAttributes.ContentState`
 * of the Live Activity (Figma "Live Updates / Activities", node 11737:49231). Text is returned as
 * string resource keys; the English is noted on each key.
 */
internal data class VisitActivityState(
    /** "Gallery 101", "Your journey", "Journey complete". */
    val title: UiText,
    /** "Stop 1 of 12", or "12 stops". */
    val progress: UiText,
    /** "Turn left in 50 m", or `null` (iOS: empty). */
    val instruction: UiText?,
    /** "L1", or empty. */
    val level: String,
    val isFinished: Boolean,
)

/** Rules of the visit's ongoing notification (iOS `VisitLiveActivity`). Pure; covered by tests. */
internal object VisitActivity {
    /** The shortest gap between two updates. Guidance changes with every position. */
    internal const val MINIMUM_UPDATE_INTERVAL_SECONDS: Double = 5.0

    /**
     * The notification content for a visit card state. Keys: `visit_your_journey` "Your journey",
     * `visit_stop_n_of_n` "Stop %1$d of %2$d", `visit_n_stops` "%d stops" (plural),
     * `visit_journey_complete` "Journey complete", `guidance_arrived` "You have arrived".
     */
    internal fun contentState(card: VisitCardState, journey: Journey, activeStopId: String?, instruction: UiText?): VisitActivityState {
        val numbers = VisitPlan.numbers(journey)
        val total = numbers.size
        val stops = UiText.Resource("visit_n_stops", listOf(total))
        val progress = activeStopId?.let { numbers[it] }?.let { UiText.Resource("visit_stop_n_of_n", listOf(it, total)) } ?: stops
        return when (card) {
            VisitCardState.Starting -> VisitActivityState(UiText.Resource("visit_your_journey"), progress, null, "", false)
            is VisitCardState.Walking -> VisitActivityState(UiText.Verbatim(card.stop), progress, instruction, "", false)
            is VisitCardState.NextStopOnOtherLevel ->
                VisitActivityState(UiText.Verbatim(card.stop), progress, instruction, "L${card.level}", false)
            is VisitCardState.AtStop ->
                VisitActivityState(UiText.Verbatim(card.stop), progress, UiText.Resource("guidance_arrived"), "", false)
            VisitCardState.Finished -> VisitActivityState(UiText.Resource("visit_journey_complete"), stops, null, "", true)
        }
    }

    /**
     * Whether [state] is sent now. Step changes (title, level, finished) go at once; instruction
     * changes wait [MINIMUM_UPDATE_INTERVAL_SECONDS] after the last update. An unchanged state is
     * not sent.
     */
    internal fun shouldSend(
        state: VisitActivityState,
        last: VisitActivityState?,
        secondsSinceLast: Double,
        minimumIntervalSeconds: Double = MINIMUM_UPDATE_INTERVAL_SECONDS,
    ): Boolean {
        if (state == last) return false
        val isStepChange = last == null || state.title != last.title || state.level != last.level || state.isFinished
        return isStepChange || secondsSinceLast >= minimumIntervalSeconds
    }
}
