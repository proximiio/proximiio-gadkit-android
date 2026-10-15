package io.proximi.gadkit.wristband

import android.content.Context
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.GadConfiguration
import io.proximi.gadkit.UiText
import io.proximi.gadkit.wristband.BindingState
import io.proximi.gadkit.wristband.WristbandConnecting
import io.proximi.gadkit.wristband.WristbandCopy
import io.proximi.gadkit.wristband.WristbandLocationAsk
import io.proximi.sdk.blueiot.binding.BlueiotBindingConfiguration
import io.proximi.sdk.blueiot.binding.BlueiotBindingError
import io.proximi.sdk.blueiot.binding.BlueiotBindingState
import io.proximi.sdk.blueiot.binding.BlueiotWristbandBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 */
public class WristbandSession(context: Context, configuration: GadConfiguration, scope: CoroutineScope) : WristbandConnecting {
    /** The binding client. One per process; `VenueService` attaches its position provider. */
    public val binding: BlueiotWristbandBinding = BlueiotWristbandBinding(context.applicationContext, configuration(configuration))

    /** The location dialog [ask] shows. The pairing screen hosts it. */
    internal val locationDialog = LocationDialog()

    private val mutableState = MutableStateFlow(binding.state)

    /** The latest binding state. */
    public val state: StateFlow<BlueiotBindingState> = mutableState.asStateFlow()

    /** [state] as core's mirror. */
    public val bindingState: StateFlow<BindingState> =
        state.map { it.toCore() }.stateIn(scope, SharingStarted.Eagerly, binding.state.toCore())

    /** `true` while a session is active. A bind in progress does not change it. */
    public val isActive: Boolean get() = state.value.session != null

    /** The notice for an ended session (`WristbandCopy.notice`), or `null` while none has ended. */
    public val endNotice: UiText? get() = (bindingState.value as? BindingState.Ended)?.let { WristbandCopy.notice(it.reason) }

    init {
        scope.launch { binding.restore() }
        scope.launch {
            binding.stateChanges().collect {
                mutableState.value = it
                if (FieldLog.isEnabled) FieldLog.log("wristband: ${WristbandCopy.logLine(it.toCore())}")
            }
        }
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
    public suspend fun end() {
        try {
            binding.end()
        } catch (error: BlueiotBindingError) {
            throw error.toCore()
        }
    }

    public companion object {
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
