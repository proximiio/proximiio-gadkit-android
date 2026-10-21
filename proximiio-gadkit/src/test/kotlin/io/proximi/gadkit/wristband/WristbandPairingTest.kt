package io.proximi.gadkit.wristband

import io.proximi.gadkit.UiText
import io.proximi.gadkit.wristband.WristbandPairing.Step
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeConnector(
    /** One answer per bind: `null` succeeds, an error is thrown. */
    results: List<BindingError?>,
    var locationAnswer: WristbandLocationAsk? = null,
) : WristbandConnecting {
    private val results = results.toMutableList()
    val bound = mutableListOf<String>()
    val asked = mutableListOf<WristbandLocationAsk>()

    override suspend fun locationAsk(): WristbandLocationAsk? = locationAnswer

    override suspend fun ask(ask: WristbandLocationAsk) {
        asked += ask
    }

    override suspend fun bind(label: String) {
        bound += label
        val result = if (results.isEmpty()) BindingError.Network(IOException("offline")) else results.removeAt(0)
        if (result != null) throw result
    }
}

/** Port of guggenheim-ios `WristbandPairingTests`. */
class WristbandPairingTest {
    @Test
    fun scannedPayloadIsBoundAsTrimmedText() = runBlocking {
        val connector = FakeConnector(listOf(null))
        val pairing = WristbandPairing(connector)
        pairing.startScanning()
        pairing.submit(" GAD-0x3B9B9EFA \n")
        assertEquals(Step.Connected("GAD-0x3B9B9EFA"), pairing.step)
        assertEquals(listOf("GAD-0x3B9B9EFA"), connector.bound)
    }

    @Test
    fun emptyCodeStaysOnTheScreenWithAMessage() = runBlocking {
        val connector = FakeConnector(emptyList())
        val pairing = WristbandPairing(connector)
        pairing.enterCodeManually()
        pairing.submit("   ")
        assertEquals(Step.ManualEntry, pairing.step)
        assertNotNull(pairing.invalidCodeMessage)
        assertTrue(connector.bound.isEmpty())
    }

    @Test
    fun typedCodeTheRelayRejectsReturnsToTheField() = runBlocking {
        val connector = FakeConnector(listOf(BindingError.InvalidTagId()))
        val pairing = WristbandPairing(connector)
        pairing.enterCodeManually()
        pairing.submit("abc")
        assertEquals(Step.ManualEntry, pairing.step)
        assertEquals("abc", pairing.enteredCode)
        assertEquals(WristbandCopy.message(BindingError.InvalidTagId()), pairing.invalidCodeMessage)
    }

    @Test
    fun failuresCountUpWithTheErrorMessageAndRetryUsesTheSameLabel() = runBlocking {
        val refused = BindingError.TagAlreadyBound()
        val connector = FakeConnector(listOf(refused, refused, null))
        val pairing = WristbandPairing(connector)
        val message = WristbandCopy.message(refused)

        pairing.startScanning()
        pairing.submit("1000045662")
        assertEquals(Step.Failed("1000045662", message, attempts = 1), pairing.step)
        pairing.retry()
        assertEquals(Step.Failed("1000045662", message, attempts = 2), pairing.step)
        pairing.retry()
        assertEquals(Step.Connected("1000045662"), pairing.step)
        assertEquals(listOf("1000045662", "1000045662", "1000045662"), connector.bound)
    }

    @Test
    fun locationIsAskedBeforeTheBindWhenTheBindingNeedsIt() = runBlocking {
        val connector = FakeConnector(listOf(null), locationAnswer = WristbandLocationAsk.PRECISE_LOCATION)
        val pairing = WristbandPairing(connector)
        pairing.submit("1000045662")
        assertEquals(listOf(WristbandLocationAsk.PRECISE_LOCATION), connector.asked)
        assertEquals(Step.Connected("1000045662"), pairing.step)
    }

    @Test
    fun scanningCanBeCancelledAndCameraDenialIsShown() {
        val pairing = WristbandPairing(FakeConnector(emptyList()))
        pairing.startScanning()
        assertEquals(Step.Scanning, pairing.step)
        pairing.cancelScanning()
        assertEquals(Step.Intro, pairing.step)
        pairing.cameraAccessDenied()
        assertEquals(Step.CameraDenied, pairing.step)
    }
}

/** Port of guggenheim-ios `WristbandCopyTests`. Keys stand in for the English text. */
class WristbandCopyTest {
    @Test
    fun takeOverRefusalNamesWhatThePhoneSent() {
        val error = BindingError.NotInAuthorizedZone(LocationCause.APPROXIMATE_LOCATION)
        assertEquals(WristbandCopy.message(LocationCause.APPROXIMATE_LOCATION), WristbandCopy.message(error))
        assertEquals(UiText.Resource("wristband_cause_approximate_location"), WristbandCopy.message(error))
    }

    @Test
    fun rateLimitNamesTheWaitInWholeSeconds() {
        assertEquals(
            UiText.Resource("wristband_error_rate_limited_seconds", listOf(5)),
            WristbandCopy.message(BindingError.RateLimited(retryAfterSeconds = 4.2)),
        )
        assertEquals(
            UiText.Resource("wristband_error_rate_limited"),
            WristbandCopy.message(BindingError.RateLimited(retryAfterSeconds = null)),
        )
    }

    @Test
    fun refusedSwapAndSigningOutageHaveTheirOwnText() {
        assertEquals(UiText.Resource("wristband_error_swap_bands_apart"), WristbandCopy.message(BindingError.SwapBandsApart()))
        assertEquals(UiText.Resource("wristband_error_swap_at_reception"), WristbandCopy.message(BindingError.SwapAtReception()))
        assertEquals(UiText.Resource("wristband_error_signing_unavailable"), WristbandCopy.message(BindingError.SigningUnavailable(retryAfterSeconds = 3.0)))
    }

    @Test
    fun supersededSessionTellsTheVisitorAnotherPhoneScannedTheBand() {
        assertEquals(UiText.Resource("wristband_notice_superseded"), WristbandCopy.notice(BindingEndReason.Superseded))
        assertEquals(WristbandCopy.notice(BindingEndReason.UserEnded), WristbandCopy.notice(BindingEndReason.Unknown("NEW_REASON")))
    }

    @Test
    fun logLineHoldsNoSessionDetails() {
        assertEquals("ended, TIMEOUT", WristbandCopy.logLine(BindingState.Ended(BindingEndReason.Timeout)))
        assertEquals("unbound", WristbandCopy.logLine(BindingState.Unbound))
    }

    @Test
    fun connectAsksForLocationThenBindsTheTrimmedCode() = runBlocking {
        val connector = FakeConnector(listOf(null), locationAnswer = WristbandLocationAsk.PERMISSION)
        connector.connect(" GAD-0x3B9B9EFA \n")
        assertEquals(listOf(WristbandLocationAsk.PERMISSION), connector.asked)
        assertEquals(listOf("GAD-0x3B9B9EFA"), connector.bound)
    }

    @Test
    fun connectWithAnEmptyCodeThrowsInvalidTagIdAndBindsNothing() = runBlocking {
        val connector = FakeConnector(listOf(null))
        assertFailsWith<BindingError.InvalidTagId> { connector.connect("  ") }
        assertTrue(connector.bound.isEmpty())
    }
}
