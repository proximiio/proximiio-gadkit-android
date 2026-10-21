package io.proximi.gadkit.ui

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import io.proximi.gadkit.R
import io.proximi.gadkit.UiText

/**
 * Maps the keys core returns ([UiText.Resource]) to string resources. Explicit ids rather
 * than `getIdentifier`, so resource shrinking keeps them.
 */
private val strings: Map<String, Int> = mapOf(
    // WristbandCopy.message(error)
    "wristband_error_tag_already_bound" to R.string.this_wristband_is_already_in_use_please,
    "wristband_error_tag_not_available" to R.string.this_wristband_is_not_active_please_ask,
    "wristband_error_tag_not_at_reception" to R.string.connect_this_wristband_at_the_reception_desk,
    "wristband_error_tag_not_issued" to R.string.this_wristband_has_not_been_issued_yet,
    "wristband_error_invalid_tag_id" to R.string.this_is_not_a_wristband_code_check,
    "wristband_error_rate_limited" to R.string.too_many_attempts_try_again_in_a,
    "wristband_error_network" to R.string.the_museum_s_server_cannot_be_reached,
    "wristband_error_server" to R.string.the_museum_s_server_failed_try_again,
    "wristband_error_session_ended" to R.string.the_wristband_session_has_ended_connect_the,
    // WristbandCopy.message(cause)
    "wristband_cause_permission_missing" to R.string.to_take_over_this_wristband_allow_location,
    "wristband_cause_approximate_location" to R.string.to_take_over_this_wristband_turn_on,
    "wristband_cause_no_fresh_fix" to R.string.your_location_could_not_be_determined_in,
    "wristband_cause_simulated_location" to R.string.this_phone_reports_a_simulated_location_turn,
    "wristband_cause_rejected_by_relay" to R.string.to_take_over_this_wristband_be_inside,
    // WristbandCopy.notice(reason)
    "wristband_notice_superseded" to R.string.your_wristband_was_scanned_by_another_phone,
    "wristband_notice_visit_ended" to R.string.your_visit_has_ended,
    "wristband_notice_returned" to R.string.the_wristband_was_returned_thank_you_for,
    "wristband_notice_timeout" to R.string.the_wristband_stopped_reporting_please_ask_the,
    "wristband_notice_left_venue" to R.string.the_wristband_left_the_museum,
    "wristband_notice_staff_revoked" to R.string.the_staff_released_this_wristband,
    "wristband_notice_expired" to R.string.your_visit_time_is_over,
    "wristband_notice_replaced" to R.string.this_wristband_session_was_replaced_by_a,
    "wristband_notice_session_lost" to R.string.the_connection_to_your_wristband_was_lost,
    // GeofenceNotifier
    "geofence_entered" to R.string.you_entered_this_area,
    "geofence_exited" to R.string.you_left_this_area,
    "visit_no_accessible_route" to R.string.no_route_to_this_stop_with_your,
    // VisitCardText, VisitActivity
    "visit_your_journey" to R.string.your_journey_2,
    "visit_stop_n_of_n" to R.string.stop_n_of_n,
    "visit_journey_complete" to R.string.journey_complete_2,
    "visit_journey_complete_caps" to R.string.journey_complete,
    "visit_next_stop_is" to R.string.next_stop_is_x,
    "visit_last_stop" to R.string.last_stop,
    "visit_gallery_x" to R.string.gallery_x,
    "visit_next_stop" to R.string.next_stop,
    "visit_current_artwork" to R.string.current_artwork,
    "visit_go_to_level" to R.string.go_to_level_n,
    "visit_walked_your_route" to R.string.you_ve_walked_your_route,
    "visit_level_n" to R.string.level_n,
)

/** Keys whose first argument is the plural count. */
private val plurals: Map<String, Int> = mapOf(
    "wristband_error_rate_limited_seconds" to R.plurals.too_many_attempts_try_again_in_n,
    "visit_n_stops" to R.plurals.n_stops,
)

/** The text for [this]. An unmapped key shows as is, so a missing entry is visible, not blank. */
internal fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Verbatim -> text
    is UiText.Resource -> {
        val args = args.toTypedArray()
        @PluralsRes val plural = plurals[key]
        @StringRes val string = strings[key]
        when {
            plural != null -> resources.getQuantityString(plural, (args.first() as Number).toInt(), *args)
            string != null -> resources.getString(string, *args)
            else -> key
        }
    }
}

internal fun UiText.resolve(context: Context): String = resolve(context.resources)

@Composable
internal fun UiText.asString(): String = resolve(LocalResources.current)
