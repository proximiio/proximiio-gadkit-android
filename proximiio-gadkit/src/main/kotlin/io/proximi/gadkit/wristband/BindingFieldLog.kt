package io.proximi.gadkit.wristband

import io.proximi.gadkit.MeasurementLog
import io.proximi.gadkit.FieldLog
import io.proximi.sdk.blueiot.binding.BlueiotBindingDiagnostics
import io.proximi.sdk.blueiot.binding.BlueiotBindingLink
import io.proximi.sdk.blueiot.binding.BlueiotBindingState
import io.proximi.sdk.blueiot.binding.BlueiotWristbandBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Field log lines of a wristband binding in the fixed form the relay-api parses for its wristband
 * timeline (relay-api, Metis T-1797, 2026-11-16), as iOS `BindingFieldLog` (1.49.2):
 *
 *     relay stream: open · binding <uuid> · <sse|ws|poll>
 *     relay stream: closed · binding <uuid> · <cause>
 *     relay position: ts <engine time, epoch ms> · age <ms>
 *
 * A stream is open while the binding's link is online. The cause of a close is the state that
 * followed, plus the binding's last error when it has one. A position line is written for the first
 * position and then at most every [POSITION_INTERVAL_MILLIS].
 *
 * For a host app on `ProximiMapView`, which owns its binding, [follow] with `logsStates = true` also
 * writes the `wristband:`, `wristband session:` and `binding diagnostics:` lines that
 * [WristbandSession] writes for `ProximiMap`.
 */
internal object BindingFieldLog {
    /** Milliseconds between two `relay position:` lines. */
    const val POSITION_INTERVAL_MILLIS: Long = 5_000

    /** Milliseconds between two `binding diagnostics:` lines while a session is active. */
    private const val DIAGNOSTICS_INTERVAL_MILLIS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobs: List<Job> = emptyList()

    /**
     * Follows [binding] until the next call. [logsStates] adds the state, session and diagnostics
     * lines. Does nothing while the field log is off.
     */
    @Synchronized
    fun follow(binding: BlueiotWristbandBinding, logsStates: Boolean) {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        MeasurementLog.latestRelayPosition = null
        if (!FieldLog.isEnabled) return
        val started = mutableListOf<Job>()
        started += scope.launch {
            val stream = StreamLog()
            binding.stateChanges().collect { state ->
                val diagnostics = binding.diagnostics()
                if (logsStates) {
                    FieldLog.log("wristband: ${WristbandCopy.logLine(state.toCore())}")
                    BindingLog.session(state)?.let { FieldLog.log("wristband session: $it") }
                    FieldLog.log("binding diagnostics: ${BindingLog.diagnostics(diagnostics)}")
                }
                stream.lines(state, diagnostics).forEach { FieldLog.log(it) }
            }
        }
        started += scope.launch {
            var last: Long? = null
            val rate = MeasurementLog.RateLog()
            binding.positionProvider.samples().collect { sample ->
                val now = System.nanoTime() / 1_000_000
                MeasurementLog.latestRelayPosition = sample
                rate.position(now / 1000.0)?.let { FieldLog.log(it) }
                if (!logsPosition(now, last)) return@collect
                last = now
                FieldLog.log(positionLine(sample.timestamp, System.currentTimeMillis()))
            }
        }
        if (logsStates) {
            started += scope.launch {
                while (isActive) {
                    delay(DIAGNOSTICS_INTERVAL_MILLIS)
                    if (binding.state !is BlueiotBindingState.Active) continue
                    FieldLog.log("binding diagnostics: ${BindingLog.diagnostics(binding.diagnostics())}")
                }
            }
        }
        jobs = started
    }

    /** Whether a position at [now] gets a line, [last] being the time of the last line (milliseconds). */
    fun logsPosition(now: Long, last: Long?): Boolean = last == null || now - last >= POSITION_INTERVAL_MILLIS

    /** "relay position: ts <engine ms> · age <ms>". */
    fun positionLine(timestampMillis: Long, nowMillis: Long): String = "relay position: ts $timestampMillis · age ${nowMillis - timestampMillis}"

    /** Turns the binding states into the `relay stream:` lines. */
    class StreamLog {
        /** The binding of the open stream. */
        var open: UUID? = null
            private set

        fun lines(state: BlueiotBindingState, diagnostics: BlueiotBindingDiagnostics): List<String> {
            val lines = mutableListOf<String>()
            val online = (state as? BlueiotBindingState.Active)?.takeIf { it.link == BlueiotBindingLink.ONLINE }?.session?.bindingID
            val wasOpen = open
            if (wasOpen != null && wasOpen != online) {
                var cause = WristbandCopy.logLine(state.toCore())
                diagnostics.lastError?.let { cause += " · $it" }
                lines += "relay stream: closed · binding ${wasOpen.toString().lowercase(Locale.ROOT)} · $cause"
                open = null
            }
            if (online != null && open == null) {
                lines += "relay stream: open · binding ${online.toString().lowercase(Locale.ROOT)} · ${transport(diagnostics)}"
                open = online
            }
            return lines
        }

        companion object {
            fun transport(diagnostics: BlueiotBindingDiagnostics): String = when {
                diagnostics.usesPolling -> "poll"
                diagnostics.transport.rawValue == "serverSentEvents" -> "sse"
                else -> "ws"
            }
        }
    }
}
