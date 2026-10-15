package io.proximi.gadkit.visit

import io.proximi.gadkit.UiText
import io.proximi.gadkit.roundedInt
import java.time.Duration
import java.time.Instant

/**
 * The full-screen "Where are you?" choice (Figma nodes 6902:38073 EC04b, 6902:38099 EC04c), as iOS `LocationChoice` and the builders in `VisitView`. Keys are string
 * resources; the English is noted on each. Pure; covered by tests.
 */
internal data class LocationChoice(
    /** The [LocationAmbiguity.kind], or `manual` for EC04c. */
    val kind: String,
    val questionLight: UiText?,
    val questionBold: UiText,
    val caption: UiText?,
    val options: List<Option>,
) {
    internal data class Option(val id: String, val title: UiText, val subtitle: UiText? = null, val action: Action)

    internal sealed interface Action {
        /** Make this journey stop the active one. */
        data class ActivateStop(val stopId: String) : Action

        /** The visitor is in this gallery. */
        data class InGallery(val name: String) : Action

        /** None of the options; close the prompt. */
        data object SomewhereElse : Action
    }

    internal companion object {
        /** Kind of the manual choice (EC04c). */
        internal const val MANUAL: String = "manual"

        /** A prompt kind is not shown again for this long after the visitor answers it. */
        internal val QUIET_PERIOD: Duration = Duration.ofSeconds(120)

        /** `location_somewhere_else` "Somewhere else". */
        internal val somewhereElse: Option =
            Option("somewhere-else", UiText.Resource("location_somewhere_else"), action = Action.SomewhereElse)

        /**
         * EC04b: `location_between_galleries` "You're between galleries." / `location_which_one_are_you_in`
         * "Which one are you in?". EC04d is a sheet, not a choice: `null`.
         */
        internal fun of(ambiguity: LocationAmbiguity): LocationChoice? = when (ambiguity) {
            is LocationAmbiguity.BetweenGalleries -> LocationChoice(
                kind = ambiguity.kind,
                questionLight = UiText.Resource("location_between_galleries"),
                questionBold = UiText.Resource("location_which_one_are_you_in"),
                caption = null,
                options = ambiguity.galleries.map { Option(it.id, UiText.Verbatim(it.title), action = Action.InGallery(it.title)) } + somewhereElse,
            )
            is LocationAmbiguity.LowConfidence -> null
        }

        /**
         * EC04c: the visitor picks the stop they are at among the pending and active stops.
         * `location_where_are_you` "Where are you?", caption `location_pick_the_artwork`
         * "Pick the artwork you are at", subtitles `visit_level_n` "Level %d".
         */
        internal fun manual(journey: Journey): LocationChoice = LocationChoice(
            kind = MANUAL,
            questionLight = null,
            questionBold = UiText.Resource("location_where_are_you"),
            caption = UiText.Resource("location_pick_the_artwork"),
            options = journey.stops
                .filter { it.state == StopState.PENDING || it.state == StopState.ACTIVE }
                .map { stop ->
                    Option(
                        id = stop.id,
                        title = UiText.Verbatim(stop.title),
                        subtitle = stop.level?.let { UiText.Resource("visit_level_n", listOf(it.roundedInt())) },
                        action = Action.ActivateStop(stop.id),
                    )
                } + somewhereElse,
        )

        /** True when the visitor answered a prompt of [kind] less than [QUIET_PERIOD] (120 s) ago. */
        internal fun isQuiet(kind: String, answered: Map<String, Instant>, now: Instant): Boolean {
            val time = answered[kind] ?: return false
            return Duration.between(time, now) < QUIET_PERIOD
        }
    }
}
