package io.proximi.gadkit.wristband

import io.proximi.gadkit.wristband.WristbandConnecting
import io.proximi.gadkit.wristband.WristbandPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One run of the "Connect your wristband" flow: core's [WristbandPairing] state machine, the
 * scope its bind runs in, and the location dialog its connector shows. iOS creates a new
 * `WristbandPairing` each time the flow opens; so does the app, with [WristbandPairingFlow.of].
 *
 * [scope] outlives the screen (a ViewModel's scope), so a bind in flight survives a recreated
 * activity, as an iOS `Task` survives a redrawn view.
 */
public class WristbandPairingFlow internal constructor(
    connector: WristbandConnecting,
    private val scope: CoroutineScope,
    /** Hosted by the pairing screen while a bind may ask for location; `null` for a connector that never asks. */
    internal val locationDialog: LocationDialog? = null,
) {
    internal val pairing = WristbandPairing(connector)

    private var job: Job? = null

    /** Binds a scanned QR payload or a typed code. Ignored while a bind is in flight. */
    public fun submit(code: String): Unit = launchOnce { pairing.submit(code) }

    /** "Try again" on the failure screen. */
    public fun retry(): Unit = launchOnce { pairing.retry() }

    private fun launchOnce(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch { block() }
    }

    public companion object {
        /** A flow that binds through [session]. */
        public fun of(session: WristbandSession, scope: CoroutineScope): WristbandPairingFlow = WristbandPairingFlow(session, scope, session.locationDialog)
    }
}
