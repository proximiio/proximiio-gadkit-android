package io.proximi.gadkit.wristband

import io.proximi.gadkit.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/** What to ask for before a bind. */
public enum class WristbandLocationAsk {
    /** Location permission. Asked only while it is undetermined. */
    PERMISSION,

    /** Precise location, when the visitor allowed approximate location only. */
    PRECISE_LOCATION,
}

/** Binds the phone to a wristband. The SDK binding in the app; a fake in tests and in the debug pairing demo. */
public interface WristbandConnecting {
    /** What to ask for before a bind, or `null` when nothing can or needs to be asked. */
    public suspend fun locationAsk(): WristbandLocationAsk?

    /** Shows the system dialog for [ask] and returns when it is answered. */
    public suspend fun ask(ask: WristbandLocationAsk)

    /** Binds the phone to the band with [label]. Returns when the session is active. Throws [BindingError]. */
    public suspend fun bind(label: String)
}

/**
 * State of the "Connect your wristband" flow (Figma section "Wristband", node 9330:83108).
 *
 * Steps: intro → scan the QR code or enter the code → looking for the wristband →
 * connected, or failed. A wristband counts as connected when the relay-api accepts the
 * bind. The scanned payload or typed code goes to the relay-api unparsed; the relay-api
 * resolves every label format.
 */
internal class WristbandPairing(private val connector: WristbandConnecting) {
    internal sealed interface Step {
        /** "Connect your wristband" with "Scan QR code" and "Skip for now". */
        data object Intro : Step

        /** Camera scanner for the QR code on the back of the wristband. */
        data object Scanning : Step

        /** Text field for the code printed on the wristband. */
        data object ManualEntry : Step

        /** "Looking for your wristband": the bind request is in flight. */
        data class Looking(val label: String) : Step

        /** "Wristband connected!". */
        data class Connected(val label: String) : Step

        /**
         * "We couldn't connect your wristband" with the message for the error.
         * [attempts] selects the first or the repeated-failure variant of the screen.
         */
        data class Failed(val label: String, val message: UiText, val attempts: Int) : Step

        /** "Camera permission denied". */
        data object CameraDenied : Step
    }

    internal data class State(
        val step: Step = Step.Intro,
        val failedAttempts: Int = 0,
        /** Message under the code field: an empty code, or a code the relay-api rejected. */
        val invalidCodeMessage: UiText? = null,
        /** The last typed code. Fills the field when the entry screen is shown again. */
        val enteredCode: String = "",
    )

    private val mutableState = MutableStateFlow(State())
    internal val state: StateFlow<State> = mutableState.asStateFlow()

    internal val step: Step get() = state.value.step
    internal val failedAttempts: Int get() = state.value.failedAttempts
    internal val invalidCodeMessage: UiText? get() = state.value.invalidCodeMessage
    internal val enteredCode: String get() = state.value.enteredCode

    internal fun startScanning() {
        mutableState.update { it.copy(invalidCodeMessage = null, step = Step.Scanning) }
    }

    internal fun enterCodeManually() {
        mutableState.update { it.copy(invalidCodeMessage = null, step = Step.ManualEntry) }
    }

    internal fun cancelScanning() {
        mutableState.update { it.copy(step = Step.Intro) }
    }

    internal fun cameraAccessDenied() {
        mutableState.update { it.copy(step = Step.CameraDenied) }
    }

    /**
     * Binds the band with a scanned QR payload or a typed code. Surrounding whitespace is
     * trimmed; the rest goes to the relay-api as is. An empty code sets
     * `wristband_enter_code` "Enter the code printed on the wristband."
     */
    internal suspend fun submit(code: String) {
        val label = code.trim()
        val isTyped = step == Step.ManualEntry
        if (isTyped) mutableState.update { it.copy(enteredCode = label) }
        if (label.isEmpty()) {
            mutableState.update { it.copy(invalidCodeMessage = UiText.Resource("wristband_enter_code")) }
            return
        }
        mutableState.update { it.copy(invalidCodeMessage = null) }
        connect(label, isTyped)
    }

    internal suspend fun retry() {
        val failed = step as? Step.Failed ?: return
        connect(failed.label, isTyped = false)
    }

    private suspend fun connect(label: String, isTyped: Boolean) {
        mutableState.update { it.copy(step = Step.Looking(label)) }
        connector.locationAsk()?.let { connector.ask(it) }
        try {
            connector.bind(label)
            mutableState.update { it.copy(failedAttempts = 0, step = Step.Connected(label)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A typed code the relay-api does not know goes back to the field.
            if (isTyped && error is BindingError.InvalidTagId) {
                mutableState.update { it.copy(invalidCodeMessage = WristbandCopy.message(error), step = Step.ManualEntry) }
                return
            }
            mutableState.update {
                val attempts = it.failedAttempts + 1
                it.copy(failedAttempts = attempts, step = Step.Failed(label, WristbandCopy.message(error), attempts))
            }
        }
    }
}
