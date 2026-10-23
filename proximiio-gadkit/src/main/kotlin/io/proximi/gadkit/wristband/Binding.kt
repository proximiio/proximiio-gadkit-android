package io.proximi.gadkit.wristband

/**
 * Wristband binding types, mirroring the cases of the iOS SDK's `ProximiioBlueiot` binding
 * (`BlueiotBindingError`, `BlueiotLocationCause`, `BlueiotBindingEndReason`, `BlueiotBindingState`).
 * The app maps the Android SDK's types to these. Messages are the SDK's English descriptions.
 */
internal sealed class BindingError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    internal abstract val requestId: String?

    /** The relay refused the request as malformed. */
    internal data class InvalidRequest(val serverMessage: String? = null, override val requestId: String? = null) :
        BindingError("The relay refused the binding request as malformed.")

    /** The label is not a wristband id. */
    internal data class InvalidTagId(override val requestId: String? = null) : BindingError("This is not a wristband id.")

    internal data class AppTokenRejected(override val requestId: String? = null) : BindingError("The relay refused the app token.")

    internal data class Forbidden(val serverMessage: String? = null, override val requestId: String? = null) :
        BindingError("The relay refused this request.")

    /** The configured URL is not a relay-api install. */
    internal data class NotARelayApi(override val requestId: String? = null) : BindingError("This URL is not a relay-api install.")

    internal data class TagNotAvailable(override val requestId: String? = null) : BindingError("This wristband is not active.")

    internal data class TagNotAtReception(override val requestId: String? = null) :
        BindingError("This wristband must be at the reception desk to be connected.")

    internal data class TagNotIssued(override val requestId: String? = null) : BindingError("This wristband has not been issued yet.")

    internal data class TagAlreadyBound(override val requestId: String? = null) : BindingError("This wristband is already in use.")

    /** A take-over refused with `not_in_authorized_zone`; [locationCause] is what the phone sent. */
    internal data class NotInAuthorizedZone(
        val locationCause: LocationCause,
        val serverMessage: String? = null,
        override val requestId: String? = null,
    ) : BindingError("The wristband could not be taken over from this location.")

    internal data class BindingClosed(val reason: BindingEndReason, override val requestId: String? = null) :
        BindingError("The visit has ended (${reason.rawValue}).")

    internal data class SessionLost(override val requestId: String? = null) : BindingError("The wristband session was lost.")

    internal data class RateLimited(val retryAfterSeconds: Double? = null, override val requestId: String? = null) :
        BindingError("Too many wristband changes in a short time.")

    /** A swap to a new wristband outside reception, while the current one is not next to it. The session is kept. */
    internal data class SwapBandsApart(override val requestId: String? = null) : BindingError("The current wristband is not next to the new one.")

    /** A swap outside reception while the current wristband is silent. The session is kept. */
    internal data class SwapAtReception(override val requestId: String? = null) : BindingError("Your wristband isn't reachable.")

    /** The relay cannot sign wristband sessions for a moment. Any session is kept. */
    internal data class SigningUnavailable(val retryAfterSeconds: Double? = null, override val requestId: String? = null) :
        BindingError("The relay cannot issue wristband sessions right now.")

    internal data class Server(val status: Int, override val requestId: String? = null) : BindingError("The relay failed (HTTP $status).")

    internal data class Unexpected(
        val code: String,
        val status: Int,
        val serverMessage: String? = null,
        override val requestId: String? = null,
    ) : BindingError("The relay answered $code (HTTP $status).")

    internal data class InvalidResponse(val status: Int? = null, override val requestId: String? = null) :
        BindingError("The relay's answer could not be read.")

    internal data class Network(val underlying: Throwable) : BindingError("The relay could not be reached.", underlying) {
        override val requestId: String? get() = null
    }
}

/** What the phone sent with a take-over the relay refused. */
internal enum class LocationCause { PERMISSION_MISSING, APPROXIMATE_LOCATION, NO_FRESH_FIX, SIMULATED_LOCATION, REJECTED_BY_RELAY }

/** Why a binding session ended. Unknown relay reasons keep their raw value. */
internal sealed class BindingEndReason(val rawValue: String) {
    internal data object Returned : BindingEndReason("RETURNED")
    internal data object Timeout : BindingEndReason("TIMEOUT")
    internal data object LeftVenue : BindingEndReason("LEFT_VENUE")
    internal data object UserEnded : BindingEndReason("USER_ENDED")
    internal data object StaffRevoked : BindingEndReason("STAFF_REVOKED")
    internal data object Expired : BindingEndReason("EXPIRED")
    internal data object Superseded : BindingEndReason("SUPERSEDED")
    internal data object Replaced : BindingEndReason("REPLACED")
    internal data object SessionLost : BindingEndReason("SESSION_LOST")
    internal data class Unknown(val raw: String) : BindingEndReason(raw)

    internal companion object {
        internal fun of(rawValue: String): BindingEndReason =
            listOf(Returned, Timeout, LeftVenue, UserEnded, StaffRevoked, Expired, Superseded, Replaced, SessionLost)
                .firstOrNull { it.rawValue == rawValue } ?: Unknown(rawValue)
    }
}

internal enum class BindingLink(val rawValue: String) {
    CONNECTING("connecting"),
    ONLINE("online"),
    RECONNECTING("reconnecting"),
}

/** The binding's state. The active session is left out: core needs only the link and the signal. */
internal sealed interface BindingState {
    data object Unbound : BindingState
    data object Binding : BindingState

    /** [privacyZone]: the band is silent in a privacy zone (relay-api `private`), not lost. */
    data class Active(val link: BindingLink, val signalLost: Boolean, val privacyZone: Boolean = false) : BindingState
    data class Ended(val reason: BindingEndReason) : BindingState
}
