package io.proximi.gadkit.wristband

import io.proximi.sdk.blueiot.binding.BlueiotBindingDiagnostics
import io.proximi.sdk.blueiot.binding.BlueiotBindingSignal
import io.proximi.sdk.blueiot.binding.BlueiotBindingState
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Field log text for the wristband binding, as iOS `BindingLog`: the session of an active state
 * and the binding client's diagnostics. The session line holds the relay's binding id, which the
 * relay-api logs also hold. No line holds a tag id or a token.
 *
 * Times are ISO 8601 in UTC, to the second.
 */
internal object BindingLog {
    /**
     * "binding <id> · ends <time> · token expires <time>", plus "signal lost since <time>" while
     * the venue does not hear the band. `null` without an active session.
     */
    fun session(state: BlueiotBindingState): String? {
        val active = state as? BlueiotBindingState.Active ?: return null
        val session = active.session
        val parts = mutableListOf(
            "binding ${session.bindingID.toString().lowercase(Locale.ROOT)}",
            "ends ${iso(session.endsAt)}",
            "token expires ${iso(session.tokenExpiresAt)}",
        )
        (active.signal as? BlueiotBindingSignal.Lost)?.let { parts += "signal lost since ${iso(it.since)}" }
        return parts.joinToString(" · ")
    }

    /**
     * Every counter of [diagnostics] on one line. `lastError` and `lastRequestID` are added when
     * set; the request id matches the relay-api request log.
     */
    fun diagnostics(diagnostics: BlueiotBindingDiagnostics): String {
        val parts = mutableListOf(
            diagnostics.transport.rawValue + if (diagnostics.usesPolling) " polling" else "",
            "connections ${diagnostics.connections}",
            "reconnects ${diagnostics.reconnects}",
            "keep-alive age ${seconds(diagnostics.secondsSinceLastKeepAlive)}",
            "token left ${seconds(diagnostics.tokenSecondsRemaining)}",
            "refreshes ${diagnostics.refreshes}",
            "rebinds ${diagnostics.automaticRebinds}",
            "stale closes ${diagnostics.staleClosesIgnored}",
            "positions ${diagnostics.positionsDelivered}",
            "repeats dropped ${diagnostics.repeatedPositionsDropped}",
        )
        diagnostics.lastError?.let { parts += "last error $it" }
        diagnostics.lastRequestID?.let { parts += "request $it" }
        return parts.joinToString(" · ")
    }

    /** Epoch milliseconds as ISO 8601 in UTC, to the second (`2026-10-14T09:30:00Z`). */
    fun iso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun seconds(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f s", it) } ?: "-"
}
