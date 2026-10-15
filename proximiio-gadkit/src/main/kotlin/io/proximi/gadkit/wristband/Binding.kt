package io.proximi.gadkit.wristband

/**
 * Wristband binding types, mirroring the cases of the iOS SDK's `ProximiioBlueiot` binding
 * (`BlueiotBindingError`, `BlueiotLocationCause`, `BlueiotBindingEndReason`, `BlueiotBindingState`).
 * The app maps the Android SDK's types to these. Messages are the SDK's English descriptions.
 */
public sealed class BindingError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    public abstract val requestId: String?

    /** The relay refused the request as malformed. */
    public data class InvalidRequest(val serverMessage: String? = null, override val requestId: String? = null) :
        BindingError("The relay refused the binding request as malformed.")

    /** The label is not a wristband id. */
    public data class InvalidTagId(override val requestId: String? = null) : BindingError("This is not a wristband id.")

    public data class AppTokenRejected(override val requestId: String? = null) : BindingError("The relay refused the app token.")

    public data class Forbidden(val serverMessage: String? = null, override val requestId: String? = null) :
        BindingError("The relay refused this request.")

    /** The configured URL is not a relay-api install. */
    public data class NotARelayApi(override val requestId: String? = null) : BindingError("This URL is not a relay-api install.")

    public data class TagNotAvailable(override val requestId: String? = null) : BindingError("This wristband is not active.")

    public data class TagNotAtReception(override val requestId: String? = null) :
        BindingError("This wristband must be at the reception desk to be connected.")

    public data class TagNotIssued(override val requestId: String? = null) : BindingError("This wristband has not been issued yet.")

    public data class TagAlreadyBound(override val requestId: String? = null) : BindingError("This wristband is already in use.")

    /** A take-over refused with `not_in_authorized_zone`; [locationCause] is what the phone sent. */
    public data class NotInAuthorizedZone(
        val locationCause: LocationCause,
        val serverMessage: String? = null,
        override val requestId: String? = null,
    ) : BindingError("The wristband could not be taken over from this location.")

    public data class BindingClosed(val reason: BindingEndReason, override val requestId: String? = null) :
        BindingError("The visit has ended (${reason.rawValue}).")

    public data class SessionLost(override val requestId: String? = null) : BindingError("The wristband session was lost.")

    public data class RateLimited(val retryAfterSeconds: Double? = null, override val requestId: String? = null) :
        BindingError("Too many wristband changes in a short time.")

    public data class Server(val status: Int, override val requestId: String? = null) : BindingError("The relay failed (HTTP $status).")

    public data class Unexpected(
        val code: String,
        val status: Int,
        val serverMessage: String? = null,
        override val requestId: String? = null,
    ) : BindingError("The relay answered $code (HTTP $status).")

    public data class InvalidResponse(val status: Int? = null, override val requestId: String? = null) :
        BindingError("The relay's answer could not be read.")

    public data class Network(val underlying: Throwable) : BindingError("The relay could not be reached.", underlying) {
        override val requestId: String? get() = null
    }
}

/** What the phone sent with a take-over the relay refused. */
public enum class LocationCause { PERMISSION_MISSING, APPROXIMATE_LOCATION, NO_FRESH_FIX, SIMULATED_LOCATION, REJECTED_BY_RELAY }

/** Why a binding session ended. Unknown relay reasons keep their raw value. */
public sealed class BindingEndReason(public val rawValue: String) {
    public data object Returned : BindingEndReason("RETURNED")
    public data object Timeout : BindingEndReason("TIMEOUT")
    public data object LeftVenue : BindingEndReason("LEFT_VENUE")
    public data object UserEnded : BindingEndReason("USER_ENDED")
    public data object StaffRevoked : BindingEndReason("STAFF_REVOKED")
    public data object Expired : BindingEndReason("EXPIRED")
    public data object Superseded : BindingEndReason("SUPERSEDED")
    public data object Replaced : BindingEndReason("REPLACED")
    public data object SessionLost : BindingEndReason("SESSION_LOST")
    public data class Unknown(val raw: String) : BindingEndReason(raw)

    public companion object {
        public fun of(rawValue: String): BindingEndReason =
            listOf(Returned, Timeout, LeftVenue, UserEnded, StaffRevoked, Expired, Superseded, Replaced, SessionLost)
                .firstOrNull { it.rawValue == rawValue } ?: Unknown(rawValue)
    }
}

public enum class BindingLink(public val rawValue: String) {
    CONNECTING("connecting"),
    ONLINE("online"),
    RECONNECTING("reconnecting"),
}

/** The binding's state. The active session is left out: core needs only the link and the signal. */
public sealed interface BindingState {
    public data object Unbound : BindingState
    public data object Binding : BindingState
    public data class Active(val link: BindingLink, val signalLost: Boolean) : BindingState
    public data class Ended(val reason: BindingEndReason) : BindingState
}
