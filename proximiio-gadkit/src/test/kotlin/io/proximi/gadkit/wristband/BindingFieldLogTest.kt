package io.proximi.gadkit.wristband

import io.proximi.sdk.blueiot.binding.BlueiotBindingDiagnostics
import io.proximi.sdk.blueiot.binding.BlueiotBindingEndReason
import io.proximi.sdk.blueiot.binding.BlueiotBindingLink
import io.proximi.sdk.blueiot.binding.BlueiotBindingSession
import io.proximi.sdk.blueiot.binding.BlueiotBindingSignal
import io.proximi.sdk.blueiot.binding.BlueiotBindingState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** iOS `BindingFieldLogTests` (1.49.2): the lines the relay-api parses for its wristband timeline. */
class BindingFieldLogTest {
    private val session = BlueiotBindingSession(
        UUID.fromString("0B28C465-9DD1-4375-A148-4E192110E751"), endsAt = 2_000_000_000_000, tokenExpiresAt = 1_900_000_000_000,
    )

    private fun active(link: BlueiotBindingLink, signal: BlueiotBindingSignal = BlueiotBindingSignal.Ok) =
        BlueiotBindingState.Active(session, link, signal)

    @Test
    fun theStreamOpensOnlineAndClosesWithTheNextState() {
        val log = BindingFieldLog.StreamLog()
        val diagnostics = BlueiotBindingDiagnostics()
        assertEquals(emptyList(), log.lines(active(BlueiotBindingLink.CONNECTING), diagnostics))
        assertEquals(
            listOf("relay stream: open · binding 0b28c465-9dd1-4375-a148-4e192110e751 · sse"),
            log.lines(active(BlueiotBindingLink.ONLINE), diagnostics),
        )
        // A signal change keeps the stream.
        assertEquals(emptyList(), log.lines(active(BlueiotBindingLink.ONLINE, BlueiotBindingSignal.Lost(since = 0)), diagnostics))
        assertEquals(
            listOf("relay stream: closed · binding 0b28c465-9dd1-4375-a148-4e192110e751 · active, reconnecting · timed out"),
            log.lines(active(BlueiotBindingLink.RECONNECTING), diagnostics.copy(lastError = "timed out")),
        )
        log.lines(active(BlueiotBindingLink.ONLINE), diagnostics)
        assertEquals(
            listOf("relay stream: closed · binding 0b28c465-9dd1-4375-a148-4e192110e751 · ended, SUPERSEDED"),
            log.lines(BlueiotBindingState.Ended(BlueiotBindingEndReason.Superseded), diagnostics),
        )
        assertEquals(emptyList(), log.lines(BlueiotBindingState.Unbound, diagnostics))
    }

    @Test
    fun thePollingFallbackIsNamed() {
        assertEquals("poll", BindingFieldLog.StreamLog.transport(BlueiotBindingDiagnostics(usesPolling = true)))
        assertEquals("sse", BindingFieldLog.StreamLog.transport(BlueiotBindingDiagnostics()))
    }

    @Test
    fun aPositionLineIsWrittenAtMostEveryFiveSeconds() {
        assertTrue(BindingFieldLog.logsPosition(now = 100_000, last = null))
        assertFalse(BindingFieldLog.logsPosition(now = 104_900, last = 100_000))
        assertTrue(BindingFieldLog.logsPosition(now = 105_000, last = 100_000))
        assertEquals(
            "relay position: ts 1794822000500 · age 320",
            BindingFieldLog.positionLine(timestampMillis = 1_794_822_000_500, nowMillis = 1_794_822_000_820),
        )
    }
}
