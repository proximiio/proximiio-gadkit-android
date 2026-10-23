package io.proximi.gadkit.wristband

import io.proximi.gadkit.UiText
import kotlin.math.ceil

/**
 * Visitor-facing text for the wristband session: one message per bind error and one
 * notice per end reason, as string resource keys with the iOS English text. Ported from
 * guggenheim-ios `WristbandCopy`. Pure functions, covered by tests.
 *
 * The text is keyed on the SDK's error cases and end reasons, never on the relay-api's
 * English messages, which may change.
 */
internal object WristbandCopy {
    /**
     * The message for a failed bind or end. Keys:
     * - `wristband_error_tag_already_bound` "This wristband is already in use. Please ask the staff."
     * - `wristband_error_tag_not_available` "This wristband is not active. Please ask the staff."
     * - `wristband_error_tag_not_at_reception` "Connect this wristband at the reception desk."
     * - `wristband_error_tag_not_issued` "This wristband has not been issued yet. Please ask the staff."
     * - `wristband_error_invalid_tag_id` "This is not a wristband code. Check the code printed on the wristband."
     * - `wristband_error_rate_limited` "Too many attempts. Try again in a moment."
     * - `wristband_error_rate_limited_seconds` "Too many attempts. Try again in %d seconds."
     * - `wristband_error_swap_bands_apart` "Hold your current wristband next to the new one and scan again."
     * - `wristband_error_swap_at_reception` "Your wristband isn't reachable. Please swap it at the reception desk."
     * - `wristband_error_signing_unavailable` "The museum’s server is busy. Try again in a moment."
     * - `wristband_error_network` "The museum’s server cannot be reached. Check your connection and try again."
     * - `wristband_error_server` "The museum’s server failed. Try again in a moment."
     * - `wristband_error_session_ended` "The wristband session has ended. Connect the wristband again."
     *
     * Configuration faults are not localized: a visitor never sees them in a configured build.
     * Other errors show their own message, as iOS shows `localizedDescription`.
     */
    internal fun message(error: Throwable): UiText = when (error) {
        is BindingError.TagAlreadyBound -> res("wristband_error_tag_already_bound")
        is BindingError.NotInAuthorizedZone -> message(error.locationCause)
        is BindingError.TagNotAvailable -> res("wristband_error_tag_not_available")
        is BindingError.TagNotAtReception -> res("wristband_error_tag_not_at_reception")
        is BindingError.TagNotIssued -> res("wristband_error_tag_not_issued")
        is BindingError.InvalidTagId -> res("wristband_error_invalid_tag_id")
        is BindingError.RateLimited -> {
            val retryAfter = error.retryAfterSeconds
            if (retryAfter == null || retryAfter <= 0) {
                res("wristband_error_rate_limited")
            } else {
                UiText.Resource("wristband_error_rate_limited_seconds", listOf(ceil(retryAfter).toInt()))
            }
        }
        // A refused swap to a new wristband outside reception. The current session is kept.
        is BindingError.SwapBandsApart -> res("wristband_error_swap_bands_apart")
        is BindingError.SwapAtReception -> res("wristband_error_swap_at_reception")
        is BindingError.SigningUnavailable -> res("wristband_error_signing_unavailable")
        is BindingError.Network -> res("wristband_error_network")
        is BindingError.Server, is BindingError.InvalidResponse -> res("wristband_error_server")
        is BindingError.SessionLost, is BindingError.BindingClosed -> res("wristband_error_session_ended")
        is BindingError.AppTokenRejected -> UiText.Verbatim("The relay-api refused the app token. Check GAD_RELAY_API_APP_TOKEN.")
        is BindingError.NotARelayApi -> UiText.Verbatim("GAD_RELAY_API_BASE_URL is not a relay-api address.")
        is BindingError.InvalidRequest, is BindingError.Forbidden, is BindingError.Unexpected -> describe(error)
        else -> describe(error)
    }

    /**
     * The message for a take-over refused with `not_in_authorized_zone`, per what the phone sent:
     * - `wristband_cause_permission_missing` "To take over this wristband, allow location for this app in Settings, or ask at the reception desk."
     * - `wristband_cause_approximate_location` "To take over this wristband, turn on Precise Location for this app in Settings, or ask at the reception desk."
     * - `wristband_cause_no_fresh_fix` "Your location could not be determined in time. Try again, or ask at the reception desk."
     * - `wristband_cause_simulated_location` "This phone reports a simulated location. Turn it off, or ask at the reception desk."
     * - `wristband_cause_rejected_by_relay` "To take over this wristband, be inside the museum with location enabled, or ask at the reception desk."
     */
    internal fun message(cause: LocationCause): UiText = when (cause) {
        LocationCause.PERMISSION_MISSING -> res("wristband_cause_permission_missing")
        LocationCause.APPROXIMATE_LOCATION -> res("wristband_cause_approximate_location")
        LocationCause.NO_FRESH_FIX -> res("wristband_cause_no_fresh_fix")
        LocationCause.SIMULATED_LOCATION -> res("wristband_cause_simulated_location")
        LocationCause.REJECTED_BY_RELAY -> res("wristband_cause_rejected_by_relay")
    }

    /**
     * The notice shown on the Hub after a session ended:
     * - `wristband_notice_superseded` "Your wristband was scanned by another phone. Connect it again to follow it on this phone."
     * - `wristband_notice_visit_ended` "Your visit has ended." (user ended, unknown)
     * - `wristband_notice_returned` "The wristband was returned. Thank you for your visit."
     * - `wristband_notice_timeout` "The wristband stopped reporting. Please ask the staff."
     * - `wristband_notice_left_venue` "The wristband left the museum."
     * - `wristband_notice_staff_revoked` "The staff released this wristband."
     * - `wristband_notice_expired` "Your visit time is over."
     * - `wristband_notice_replaced` "This wristband session was replaced by a newer one."
     * - `wristband_notice_session_lost` "The connection to your wristband was lost. Connect it again."
     */
    internal fun notice(reason: BindingEndReason): UiText = when (reason) {
        BindingEndReason.Superseded -> res("wristband_notice_superseded")
        BindingEndReason.UserEnded, is BindingEndReason.Unknown -> res("wristband_notice_visit_ended")
        BindingEndReason.Returned -> res("wristband_notice_returned")
        BindingEndReason.Timeout -> res("wristband_notice_timeout")
        BindingEndReason.LeftVenue -> res("wristband_notice_left_venue")
        BindingEndReason.StaffRevoked -> res("wristband_notice_staff_revoked")
        BindingEndReason.Expired -> res("wristband_notice_expired")
        BindingEndReason.Replaced -> res("wristband_notice_replaced")
        BindingEndReason.SessionLost -> res("wristband_notice_session_lost")
    }

    /** The state for the debug log. Holds no tag id. */
    internal fun logLine(state: BindingState): String = when (state) {
        BindingState.Unbound -> "unbound"
        BindingState.Binding -> "binding"
        is BindingState.Active -> when {
            state.signalLost -> "active, ${state.link.rawValue}, signal lost"
            state.privacyZone -> "active, ${state.link.rawValue}, privacy zone"
            else -> "active, ${state.link.rawValue}"
        }
        is BindingState.Ended -> "ended, ${state.reason.rawValue}"
    }

    private fun res(key: String) = UiText.Resource(key)

    private fun describe(error: Throwable) = UiText.Verbatim(error.message ?: error.toString())
}
