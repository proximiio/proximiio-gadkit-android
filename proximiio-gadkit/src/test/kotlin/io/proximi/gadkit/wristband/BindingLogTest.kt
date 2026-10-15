package io.proximi.gadkit.wristband

import io.proximi.sdk.blueiot.binding.BlueiotBindingDiagnostics
import io.proximi.sdk.blueiot.binding.BlueiotBindingLink
import io.proximi.sdk.blueiot.binding.BlueiotBindingSession
import io.proximi.sdk.blueiot.binding.BlueiotBindingSignal
import io.proximi.sdk.blueiot.binding.BlueiotBindingState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BindingLogTest {
    private val start = 1_791_970_200_000L // 2026-10-14T09:30:00Z

    @Test
    fun theSessionLineHasTheBindingIdAndTheTimesInUTC() {
        val session = BlueiotBindingSession(
            bindingID = UUID.fromString("0E0A6D52-7F55-4E2B-9C1D-3B9B9EFA0001"),
            endsAt = start + 4 * 3_600_000,
            tokenExpiresAt = start + 15 * 60_000,
        )
        val state = BlueiotBindingState.Active(session, BlueiotBindingLink.entries.first(), BlueiotBindingSignal.Lost(start + 60_000))
        assertEquals(
            "binding 0e0a6d52-7f55-4e2b-9c1d-3b9b9efa0001 · ends 2026-10-14T13:30:00Z · token expires 2026-10-14T09:45:00Z · " +
                "signal lost since 2026-10-14T09:31:00Z",
            BindingLog.session(state),
        )
    }

    @Test
    fun noSessionLineWithoutAnActiveSession() {
        assertNull(BindingLog.session(BlueiotBindingState.Unbound))
    }

    @Test
    fun theDiagnosticsLineNamesEveryCounter() {
        val diagnostics = BlueiotBindingDiagnostics(
            lastRequestID = "req-1",
            connections = 2,
            reconnects = 1,
            usesPolling = true,
            secondsSinceLastKeepAlive = 3.25,
            tokenSecondsRemaining = null,
            positionsDelivered = 40,
        )
        assertEquals(
            "serverSentEvents polling · connections 2 · reconnects 1 · keep-alive age 3.3 s · token left - · refreshes 0 · " +
                "rebinds 0 · stale closes 0 · positions 40 · repeats dropped 0 · request req-1",
            BindingLog.diagnostics(diagnostics),
        )
    }
}
