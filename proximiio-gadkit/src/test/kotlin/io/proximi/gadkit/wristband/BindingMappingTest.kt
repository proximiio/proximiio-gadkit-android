package io.proximi.gadkit.wristband

import io.proximi.gadkit.UiText
import io.proximi.gadkit.wristband.BindingEndReason
import io.proximi.gadkit.wristband.BindingError
import io.proximi.gadkit.wristband.BindingLink
import io.proximi.gadkit.wristband.BindingState
import io.proximi.gadkit.wristband.LocationCause
import io.proximi.gadkit.wristband.WristbandCopy
import io.proximi.gadkit.wristband.WristbandLocationAsk
import io.proximi.sdk.blueiot.binding.BlueiotBindingEndReason
import io.proximi.sdk.blueiot.binding.BlueiotBindingError
import io.proximi.sdk.blueiot.binding.BlueiotBindingLink
import io.proximi.sdk.blueiot.binding.BlueiotBindingSession
import io.proximi.sdk.blueiot.binding.BlueiotBindingSignal
import io.proximi.sdk.blueiot.binding.BlueiotBindingState
import io.proximi.sdk.blueiot.binding.location.BlueiotLocationCause
import io.proximi.sdk.blueiot.binding.location.BlueiotLocationReadiness
import io.proximi.sdk.core.errors.ProximiioUnderlyingError
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The SDK's binding types onto core's mirrors, so `WristbandCopy` picks the same text as iOS. */
class BindingMappingTest {
    @Test
    fun everyErrorCaseKeepsItsPayload() {
        val cases: List<Pair<BlueiotBindingError, BindingError>> = listOf(
            BlueiotBindingError.InvalidRequest("bad", "r1") to BindingError.InvalidRequest("bad", "r1"),
            BlueiotBindingError.InvalidTagID("r2") to BindingError.InvalidTagId("r2"),
            BlueiotBindingError.AppTokenRejected("r3") to BindingError.AppTokenRejected("r3"),
            BlueiotBindingError.Forbidden("no", "r4") to BindingError.Forbidden("no", "r4"),
            BlueiotBindingError.NotARelayAPI(null) to BindingError.NotARelayApi(null),
            BlueiotBindingError.TagNotAvailable("r5") to BindingError.TagNotAvailable("r5"),
            BlueiotBindingError.TagNotAtReception("r6") to BindingError.TagNotAtReception("r6"),
            BlueiotBindingError.TagNotIssued("r7") to BindingError.TagNotIssued("r7"),
            BlueiotBindingError.TagAlreadyBound("r8") to BindingError.TagAlreadyBound("r8"),
            BlueiotBindingError.NotInAuthorizedZone(BlueiotLocationCause.APPROXIMATE_LOCATION, "zone", "r9") to
                BindingError.NotInAuthorizedZone(LocationCause.APPROXIMATE_LOCATION, "zone", "r9"),
            BlueiotBindingError.BindingClosed(BlueiotBindingEndReason.Superseded, "r10") to
                BindingError.BindingClosed(BindingEndReason.Superseded, "r10"),
            BlueiotBindingError.SessionLost(null) to BindingError.SessionLost(null),
            BlueiotBindingError.RateLimited(12.5, "r11") to BindingError.RateLimited(12.5, "r11"),
            BlueiotBindingError.SwapBandsApart("r15") to BindingError.SwapBandsApart("r15"),
            BlueiotBindingError.SwapAtReception("r16") to BindingError.SwapAtReception("r16"),
            BlueiotBindingError.SigningUnavailable(3.0, "r17") to BindingError.SigningUnavailable(3.0, "r17"),
            BlueiotBindingError.Server(503, "r12") to BindingError.Server(503, "r12"),
            BlueiotBindingError.Unexpected("invalid_transition", 409, "m", "r13") to
                BindingError.Unexpected("invalid_transition", 409, "m", "r13"),
            BlueiotBindingError.InvalidResponse(200, "r14") to BindingError.InvalidResponse(200, "r14"),
        )
        cases.forEach { (sdk, core) -> assertEquals(core, sdk.toCore()) }
    }

    @Test
    fun networkKeepsTheTransportFailure() {
        val failure = IOException("offline")
        val mapped = BlueiotBindingError.Network(ProximiioUnderlyingError(failure)).toCore()
        assertSame(failure, (mapped as BindingError.Network).underlying)
        assertEquals(UiText.Resource("wristband_error_network"), WristbandCopy.message(mapped))
    }

    @Test
    fun copyFollowsTheMappedCase() {
        assertEquals(UiText.Resource("wristband_error_tag_already_bound"), WristbandCopy.message(BlueiotBindingError.TagAlreadyBound(null).toCore()))
        assertEquals(
            UiText.Resource("wristband_cause_permission_missing"),
            WristbandCopy.message(BlueiotBindingError.NotInAuthorizedZone(BlueiotLocationCause.PERMISSION_MISSING, null, null).toCore()),
        )
        assertEquals(
            UiText.Resource("wristband_error_rate_limited_seconds", listOf(3)),
            WristbandCopy.message(BlueiotBindingError.RateLimited(2.1, null).toCore()),
        )
    }

    @Test
    fun everyLocationCauseMapsByName() {
        BlueiotLocationCause.entries.forEach { assertEquals(it.name, it.toCore().name) }
    }

    @Test
    fun endReasonsKeepTheirRawValue() {
        assertEquals(BindingEndReason.Superseded, BlueiotBindingEndReason.Superseded.toCore())
        assertEquals(BindingEndReason.SessionLost, BlueiotBindingEndReason.SessionLost.toCore())
        assertEquals(BindingEndReason.Unknown("NEW_REASON"), BlueiotBindingEndReason.Unknown("NEW_REASON").toCore())
        listOf(
            BlueiotBindingEndReason.Returned, BlueiotBindingEndReason.Timeout, BlueiotBindingEndReason.LeftVenue,
            BlueiotBindingEndReason.UserEnded, BlueiotBindingEndReason.StaffRevoked, BlueiotBindingEndReason.Expired,
            BlueiotBindingEndReason.Replaced,
        ).forEach { assertEquals(it.rawValue, it.toCore().rawValue) }
    }

    @Test
    fun statesMapWithLinkAndSignal() {
        val session = BlueiotBindingSession(UUID.randomUUID(), endsAt = 0, tokenExpiresAt = 0)
        assertEquals(BindingState.Unbound, BlueiotBindingState.Unbound.toCore())
        assertEquals(BindingState.Binding, BlueiotBindingState.Binding.toCore())
        assertEquals(
            BindingState.Active(BindingLink.ONLINE, signalLost = false),
            BlueiotBindingState.Active(session, BlueiotBindingLink.ONLINE, BlueiotBindingSignal.Ok).toCore(),
        )
        assertEquals(
            BindingState.Active(BindingLink.RECONNECTING, signalLost = true),
            BlueiotBindingState.Active(session, BlueiotBindingLink.RECONNECTING, BlueiotBindingSignal.Lost(1)).toCore(),
        )
        assertEquals(
            BindingState.Active(BindingLink.ONLINE, signalLost = false, privacyZone = true),
            BlueiotBindingState.Active(session, BlueiotBindingLink.ONLINE, BlueiotBindingSignal.PrivacyZone(1)).toCore(),
        )
        assertEquals(BindingState.Ended(BindingEndReason.UserEnded), BlueiotBindingState.Ended(BlueiotBindingEndReason.UserEnded).toCore())
        BlueiotBindingLink.entries.forEach { assertEquals(it.rawValue, it.toCore().rawValue) }
    }

    @Test
    fun readinessSelectsTheAsk() {
        assertEquals(WristbandLocationAsk.PERMISSION, BlueiotLocationReadiness.PERMISSION_NEEDED.toLocationAsk())
        assertEquals(WristbandLocationAsk.PRECISE_LOCATION, BlueiotLocationReadiness.PRECISE_LOCATION_NEEDED.toLocationAsk())
        assertNull(BlueiotLocationReadiness.READY.toLocationAsk())
        assertNull(BlueiotLocationReadiness.NOT_REQUIRED.toLocationAsk())
    }
}
