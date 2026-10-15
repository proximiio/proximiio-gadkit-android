package io.proximi.gadkit.wristband

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Turns the system location dialog into a suspension point for `WristbandSession.ask`, as iOS
 * `LocationAccess` does with CoreLocation's delegate. The dialog needs an Activity Result
 * launcher, so a screen hosts it with [LocationDialogHost]; the request lives here, so a recreated
 * activity delivers the answer to the same waiting bind.
 */
internal class LocationDialog {
    /** One dialog. [isLaunched] keeps a recreated host from showing it twice. */
    class Request internal constructor(val permissions: Array<String>) {
        internal val answer = CompletableDeferred<Unit>()
        internal var isLaunched = false
    }

    private val hosts = AtomicInteger(0)
    private val mutableRequest = MutableStateFlow<Request?>(null)

    /** The dialog to show, or `null`. */
    val request: StateFlow<Request?> = mutableRequest.asStateFlow()

    /**
     * Shows the dialog and returns when it is answered, whatever the answer. Without a
     * host on screen it returns at once: the bind is then sent without asking, which a band at the
     * reception desk does not need.
     */
    suspend fun show() {
        if (hosts.get() == 0) return
        val request = Request(PERMISSIONS)
        mutableRequest.value = request
        try {
            request.answer.await()
        } finally {
            mutableRequest.compareAndSet(request, null)
        }
    }

    internal fun answered() {
        mutableRequest.value?.answer?.complete(Unit)
    }

    internal fun attach() {
        hosts.incrementAndGet()
    }

    internal fun detach() {
        hosts.decrementAndGet()
    }

    companion object {
        /**
         * Fine and coarse together for both [io.proximi.gadkit.wristband.WristbandLocationAsk] cases:
         * Android shows the precise-or-approximate choice only for the pair, and with coarse already
         * granted the same pair asks to upgrade to precise.
         */
        val PERMISSIONS: Array<String>
            get() = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

/** Shows [dialog]'s requests with the system permission dialog while this is composed. */
@Composable
internal fun LocationDialogHost(dialog: LocationDialog) {
    val request by dialog.request.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { dialog.answered() }
    DisposableEffect(dialog) {
        dialog.attach()
        onDispose { dialog.detach() }
    }
    LaunchedEffect(request) {
        val current = request ?: return@LaunchedEffect
        if (current.isLaunched) return@LaunchedEffect
        current.isLaunched = true
        launcher.launch(current.permissions)
    }
}

/**
 * Shows the system location dialog that [WristbandSession] asks for before a take-over. A host
 * screen that calls `connect(code)` with its own scanner composes it; without it the bind goes out
 * without asking. `WristbandPairingScreen` has its own.
 */
@Composable
public fun WristbandLocationDialogHost(wristband: WristbandSession) {
    LocationDialogHost(wristband.locationDialog)
}
