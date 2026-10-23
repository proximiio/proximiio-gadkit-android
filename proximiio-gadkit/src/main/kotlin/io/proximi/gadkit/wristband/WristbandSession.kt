package io.proximi.gadkit.wristband

import android.content.Context
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.GadConfiguration
import io.proximi.gadkit.UiText
import io.proximi.gadkit.wristband.BindingState
import io.proximi.gadkit.wristband.WristbandConnecting
import io.proximi.gadkit.wristband.WristbandCopy
import io.proximi.gadkit.wristband.WristbandLocationAsk
import io.proximi.sdk.Proximiio
import io.proximi.sdk.blueiot.binding.BlueiotBindingConfiguration
import io.proximi.sdk.blueiot.binding.BlueiotBindingError
import io.proximi.sdk.blueiot.binding.BlueiotBindingSignal
import io.proximi.sdk.blueiot.binding.BlueiotBindingState
import io.proximi.sdk.blueiot.binding.BlueiotWristbandBinding
import io.proximi.sdk.setPositioningSourcePrivate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The visitor's wristband session, held by the SDK's `BlueiotWristbandBinding`, as iOS
 * `WristbandSession`. The SDK stores the session in Keystore-backed storage and confirms it with
 * the relay-api at launch (`restore()`). The app stores no wristband id of its own.
 *
 * A bind of a band another phone follows is a take-over. The relay-api accepts or rejects it, and
 * the other phone's session ends with `Superseded`. The SDK never asks for location. When the
 * relay-api needs the phone's location for a take-over, `locationReadiness()` returns the ask, and
 * [ask] shows the system dialog through [locationDialog] before the bind.
 *
 * Creating it starts nothing; [run] confirms the stored session and follows the binding state in
 * [scope], as iOS `run()`.
 */
internal class WristbandSession(context: Context, configuration: GadConfiguration, private val scope: CoroutineScope) : WristbandConnecting {
    /** The binding client. One per process; `VenueService` attaches its position provider. */
    internal val binding: BlueiotWristbandBinding = BlueiotWristbandBinding(context.applicationContext, configuration(configuration))

    /** The location dialog [ask] shows. `ProximiMapHost` and the pairing screen host it. */
    internal val locationDialog = LocationDialog()

    private val mutableState = MutableStateFlow(binding.state)

    /** The latest binding state. */
    internal val state: StateFlow<BlueiotBindingState> = mutableState.asStateFlow()

    /** `true` while a session is active. A bind in progress does not change it. */
    internal val isActive: Boolean get() = state.value.session != null

    /** The notice for an ended session (`WristbandCopy.notice`), or `null` while none has ended. */
    internal val endNotice: UiText? get() = (state.value.toCore() as? BindingState.Ended)?.let { WristbandCopy.notice(it.reason) }

    private var isRunning = false

    /** The SDK that gets the relay's privacy signal ([forwardPrivacy]). */
    private var privacySdk: Proximiio? = null

    /** The value last passed to `setPositioningSourcePrivate`, once [hasForwarded]; `null` is "not private". */
    private var forwardedPrivateSince: Long? = null
    private var hasForwarded = false

    /**
     * Confirms a session the SDK stored at the last launch with the relay-api, and follows the
     * binding state for the life of [scope]. Later calls do nothing.
     */
    internal fun run() {
        if (isRunning) return
        isRunning = true
        scope.launch { binding.restore() }
        scope.launch {
            binding.stateChanges().collect {
                mutableState.value = it
                forwardPrivacy()
                if (FieldLog.isEnabled) {
                    FieldLog.log("wristband: ${WristbandCopy.logLine(it.toCore())}")
                    BindingLog.session(it)?.let { session -> FieldLog.log("wristband session: $session") }
                    logDiagnostics()
                }
            }
        }
        // The counters of the binding client while a session is active, for the ended-session analysis.
        if (FieldLog.isEnabled) {
            scope.launch {
                while (true) {
                    delay(DIAGNOSTICS_INTERVAL_MILLIS)
                    if (this@WristbandSession.isActive) logDiagnostics()
                }
            }
        }
    }

    private suspend fun logDiagnostics() {
        FieldLog.log("binding diagnostics: ${BindingLog.diagnostics(binding.diagnostics())}")
    }

    /**
     * Passes the relay's privacy signal of every binding state to [sdk]
     * (`setPositioningSourcePrivate`, SDK 6.0.0-beta.31), starting with the current state. The SDK
     * cannot see the binding itself: a band without venue coverage in a restroom falls silent at the
     * door, and only the relay knows that it is in a privacy zone.
     */
    internal suspend fun forwardPrivacy(sdk: Proximiio) {
        privacySdk = sdk
        hasForwarded = false
        forwardPrivacy()
    }

    private suspend fun forwardPrivacy() {
        val sdk = privacySdk ?: return
        val since = privateSince(state.value)
        if (hasForwarded && forwardedPrivateSince == since) return
        hasForwarded = true
        forwardedPrivateSince = since
        FieldLog.log("wristband privacy: ${since?.let { "private since ${BindingLog.iso(it)}" } ?: "not private"}")
        sdk.setPositioningSourcePrivate(since = since)
    }

    /** What to ask for before a bind, or `null` when nothing needs to be asked. */
    override suspend fun locationAsk(): WristbandLocationAsk? = binding.locationReadiness().toLocationAsk()

    /**
     * Shows the system dialog for [ask] and returns when it is answered. Android shows nothing
     * after the visitor denied the permission permanently; the bind is then sent without a location.
     */
    override suspend fun ask(ask: WristbandLocationAsk) {
        locationDialog.show()
    }

    /**
     * Binds this phone to the band with [label], as scanned or typed. The relay-api resolves every
     * label format. The new session replaces any current one.
     *
     * @throws io.proximi.gadkit.wristband.BindingError
     */
    override suspend fun bind(label: String) {
        try {
            binding.bind(tagID = label)
        } catch (error: BlueiotBindingError) {
            throw error.toCore()
        }
    }

    /**
     * Ends the visit on the relay-api. The state becomes `Ended(UserEnded)`.
     *
     * @throws io.proximi.gadkit.wristband.BindingError when the relay-api cannot be reached; the
     *   session is kept.
     */
    internal suspend fun end() {
        try {
            binding.end()
        } catch (error: BlueiotBindingError) {
            throw error.toCore()
        }
    }

    internal companion object {
        private const val DIAGNOSTICS_INTERVAL_MILLIS = 10_000L

        /**
         * When the relay saw the band fall silent in a privacy zone (relay clock, epoch
         * milliseconds), or `null`. Pure; covered by tests.
         */
        internal fun privateSince(state: BlueiotBindingState?): Long? =
            ((state as? BlueiotBindingState.Active)?.signal as? BlueiotBindingSignal.PrivacyZone)?.since

        /**
         * The binding configuration for [configuration]. `runsInBackground` keeps the position
         * stream open after the app leaves the screen.
         */
        internal fun configuration(configuration: GadConfiguration) = BlueiotBindingConfiguration(
            relayURL = configuration.relayEndpoint,
            appToken = configuration.relayApiAppToken,
            runsInBackground = true,
        )
    }
}
