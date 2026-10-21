package io.proximi.gadkit.wristband

import io.proximi.gadkit.wristband.BindingEndReason
import io.proximi.gadkit.wristband.BindingError
import io.proximi.gadkit.wristband.BindingLink
import io.proximi.gadkit.wristband.BindingState
import io.proximi.gadkit.wristband.LocationCause
import io.proximi.gadkit.wristband.WristbandLocationAsk
import io.proximi.sdk.blueiot.binding.BlueiotBindingEndReason
import io.proximi.sdk.blueiot.binding.BlueiotBindingError
import io.proximi.sdk.blueiot.binding.BlueiotBindingLink
import io.proximi.sdk.blueiot.binding.BlueiotBindingSignal
import io.proximi.sdk.blueiot.binding.BlueiotBindingState
import io.proximi.sdk.blueiot.binding.location.BlueiotLocationCause
import io.proximi.sdk.blueiot.binding.location.BlueiotLocationReadiness

/*
 * Maps the Android SDK's binding types (`io.proximi.sdk.blueiot.binding`) onto core's mirrors
 * (`io.proximi.gadkit.wristband`), so `WristbandCopy` and `WristbandPairing` stay free of SDK
 * types, as the iOS app keys its copy on the iOS SDK's cases. Pure; covered by `BindingMappingTest`.
 */

/** The core mirror of this SDK error. Every case keeps its payload and the relay's request id. */
internal fun BlueiotBindingError.toCore(): BindingError = when (this) {
    is BlueiotBindingError.InvalidRequest -> BindingError.InvalidRequest(serverMessage, requestID)
    is BlueiotBindingError.InvalidTagID -> BindingError.InvalidTagId(requestID)
    is BlueiotBindingError.AppTokenRejected -> BindingError.AppTokenRejected(requestID)
    is BlueiotBindingError.Forbidden -> BindingError.Forbidden(serverMessage, requestID)
    is BlueiotBindingError.NotARelayAPI -> BindingError.NotARelayApi(requestID)
    is BlueiotBindingError.TagNotAvailable -> BindingError.TagNotAvailable(requestID)
    is BlueiotBindingError.TagNotAtReception -> BindingError.TagNotAtReception(requestID)
    is BlueiotBindingError.TagNotIssued -> BindingError.TagNotIssued(requestID)
    is BlueiotBindingError.TagAlreadyBound -> BindingError.TagAlreadyBound(requestID)
    is BlueiotBindingError.NotInAuthorizedZone -> BindingError.NotInAuthorizedZone(locationCause.toCore(), serverMessage, requestID)
    is BlueiotBindingError.BindingClosed -> BindingError.BindingClosed(reason.toCore(), requestID)
    is BlueiotBindingError.SessionLost -> BindingError.SessionLost(requestID)
    is BlueiotBindingError.RateLimited -> BindingError.RateLimited(retryAfter, requestID)
    is BlueiotBindingError.SwapBandsApart -> BindingError.SwapBandsApart(requestID)
    is BlueiotBindingError.SwapAtReception -> BindingError.SwapAtReception(requestID)
    is BlueiotBindingError.SigningUnavailable -> BindingError.SigningUnavailable(retryAfter, requestID)
    is BlueiotBindingError.Server -> BindingError.Server(status, requestID)
    is BlueiotBindingError.Unexpected -> BindingError.Unexpected(code, status, serverMessage, requestID)
    is BlueiotBindingError.InvalidResponse -> BindingError.InvalidResponse(status, requestID)
    is BlueiotBindingError.Network -> BindingError.Network(underlying.error)
}

internal fun BlueiotLocationCause.toCore(): LocationCause = when (this) {
    BlueiotLocationCause.PERMISSION_MISSING -> LocationCause.PERMISSION_MISSING
    BlueiotLocationCause.APPROXIMATE_LOCATION -> LocationCause.APPROXIMATE_LOCATION
    BlueiotLocationCause.NO_FRESH_FIX -> LocationCause.NO_FRESH_FIX
    BlueiotLocationCause.SIMULATED_LOCATION -> LocationCause.SIMULATED_LOCATION
    BlueiotLocationCause.REJECTED_BY_RELAY -> LocationCause.REJECTED_BY_RELAY
}

/** By raw value, so a reason the SDK reports as `Unknown` stays `Unknown` with the relay's spelling. */
internal fun BlueiotBindingEndReason.toCore(): BindingEndReason = BindingEndReason.of(rawValue)

internal fun BlueiotBindingLink.toCore(): BindingLink = when (this) {
    BlueiotBindingLink.CONNECTING -> BindingLink.CONNECTING
    BlueiotBindingLink.ONLINE -> BindingLink.ONLINE
    BlueiotBindingLink.RECONNECTING -> BindingLink.RECONNECTING
}

/** The core state. The active session is left out; the signal becomes `signalLost`. */
internal fun BlueiotBindingState.toCore(): BindingState = when (this) {
    BlueiotBindingState.Unbound -> BindingState.Unbound
    BlueiotBindingState.Binding -> BindingState.Binding
    is BlueiotBindingState.Active -> BindingState.Active(link.toCore(), signalLost = signal is BlueiotBindingSignal.Lost)
    is BlueiotBindingState.Ended -> BindingState.Ended(reason.toCore())
}

/** What to ask for before a bind, or `null` when nothing needs to be asked. */
internal fun BlueiotLocationReadiness.toLocationAsk(): WristbandLocationAsk? = when (this) {
    BlueiotLocationReadiness.PERMISSION_NEEDED -> WristbandLocationAsk.PERMISSION
    BlueiotLocationReadiness.PRECISE_LOCATION_NEEDED -> WristbandLocationAsk.PRECISE_LOCATION
    BlueiotLocationReadiness.READY, BlueiotLocationReadiness.NOT_REQUIRED -> null
}
