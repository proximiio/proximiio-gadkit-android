package io.proximi.gadkit

import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.visit.VisitMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A screen or sheet of [ProximiMap] that `ProximiMapHost` shows. The journey travels in the case,
 * so the journey screen keeps the journey it opened with while it saves the visitor's progress.
 */
internal sealed interface Presentation {
    val screen: ProximiMap.Screen

    /**
     * The presentation slot. Journey ready and the journey share one, so "Start the Journey" swaps
     * the content in place without a dismissal (iOS `JourneyReadyFlow`).
     */
    val key: String

    /** `false` for Manage journey, a bottom sheet; the others are full screen. */
    val isFullScreen: Boolean get() = true

    data object MuseumMap : Presentation {
        override val screen = ProximiMap.Screen.MUSEUM_MAP
        override val key = "museumMap"
    }

    data class JourneyReady(val journey: Journey, val meta: VisitMeta) : Presentation {
        override val screen = ProximiMap.Screen.JOURNEY_READY
        override val key = JOURNEY_KEY
    }

    /** A class, not a data class: two visits of equal journeys are two presentations. */
    class Visit(val journey: Journey, val kind: JourneyKind) : Presentation {
        override val screen = ProximiMap.Screen.JOURNEY
        override val key = JOURNEY_KEY
    }

    data object PlanJourney : Presentation {
        override val screen = ProximiMap.Screen.PLAN_JOURNEY
        override val key = "planJourney"
    }

    /**
     * `gad.hostedMap`, debuggable builds: [ProximiMapView] on the app's SDK with the default options,
     * as Nagarro's app uses it.
     */
    data object HostedMap : Presentation {
        override val screen = ProximiMap.Screen.JOURNEY
        override val key = "hostedMap"
    }

    data class ManageJourney(val meta: VisitMeta) : Presentation {
        override val screen = ProximiMap.Screen.MANAGE_JOURNEY
        override val key = "manageJourney"
        override val isFullScreen get() = false
    }

    companion object {
        const val JOURNEY_KEY = "journey"
    }
}

/**
 * One presentation at a time, as iOS `ProximiMap` does with its cover and sheet: the next one shows
 * after `ProximiMapHost` reports the previous one gone ([dismissed]), or after
 * [DISMISSAL_FALLBACK_MILLIS] when no report comes, for example without a host on screen.
 *
 * [presentation] is what the host shows; [screen] stays set until the presentation has gone.
 * Main thread only.
 */
internal class ProximiMapPresenter(
    private val scope: CoroutineScope,
    private val emit: (ProximiMapEvent) -> Unit,
    /** Called after every dismissal, before the next presentation: the stored journey is read again. */
    private val onDismissed: () -> Unit = {},
    private val log: (String) -> Unit = FieldLog::log,
) {
    private val mutablePresentation = MutableStateFlow<Presentation?>(null)
    private val mutableScreen = MutableStateFlow<ProximiMap.Screen?>(null)

    /** The presentation the host shows, or `null`. */
    val presentation: StateFlow<Presentation?> = mutablePresentation.asStateFlow()

    /** The screen on screen, or `null`. It stays set while the presentation goes away. */
    val screen: StateFlow<ProximiMap.Screen?> = mutableScreen.asStateFlow()

    /** Shown and not reported gone yet. */
    private var onScreen: Presentation? = null
    private var afterDismiss: (() -> Unit)? = null
    private var pendingToken: Any? = null

    /** Shows [presentation] now. Use [replace] when another one may be on screen. */
    fun show(presentation: Presentation) {
        mutablePresentation.value = presentation
        onScreen = presentation
        mutableScreen.value = presentation.screen
        emit(ProximiMapEvent.Opened(presentation.screen))
    }

    /**
     * Swaps the content of the slot on screen without a dismissal: Journey ready → the journey.
     * Sends `Opened` for the new screen, as iOS `visitStarted()`.
     */
    fun swap(presentation: Presentation) {
        if (mutablePresentation.value?.key != presentation.key) return show(presentation)
        mutablePresentation.value = presentation
        onScreen = presentation
        mutableScreen.value = presentation.screen
        emit(ProximiMapEvent.Opened(presentation.screen))
    }

    /**
     * Runs [next] now, or after the presentation on screen has gone. A presentation that never
     * reports its dismissal, for example without a host on screen, does not block [next]: it runs
     * after [DISMISSAL_FALLBACK_MILLIS].
     */
    fun replace(next: () -> Unit) {
        if (mutablePresentation.value == null && onScreen == null) return next()
        val token = Any()
        pendingToken = token
        afterDismiss = next
        mutablePresentation.value = null
        scope.launch {
            delay(DISMISSAL_FALLBACK_MILLIS)
            if (pendingToken !== token) return@launch
            val pending = afterDismiss ?: return@launch
            log("map: no dismissal after $DISMISSAL_FALLBACK_MILLIS ms")
            afterDismiss = null
            pendingToken = null
            onScreen?.let { emit(ProximiMapEvent.Closed(it.screen)) }
            onScreen = null
            mutableScreen.value = null
            onDismissed()
            pending()
        }
    }

    /** Hides the presentation on screen. `Closed` follows once it has gone. */
    fun hide() {
        mutablePresentation.value = null
    }

    /** Hides the presentation on screen and drops a pending next one ([ProximiMap.close]). */
    fun close() {
        afterDismiss = null
        pendingToken = null
        hide()
    }

    /**
     * The host reports that the presentation of slot [key] has left the screen. Ignored while it is
     * still the one to show (the host left the composition, not the presentation).
     */
    fun dismissed(key: String) {
        val gone = onScreen ?: return
        if (gone.key != key || mutablePresentation.value?.key == key) return
        onScreen = null
        mutableScreen.value = null
        emit(ProximiMapEvent.Closed(gone.screen))
        onDismissed()
        val next = afterDismiss
        afterDismiss = null
        pendingToken = null
        next?.invoke()
    }

    companion object {
        /** iOS: a presentation that never reached the screen gets no `onDismiss`, so the next one shows after 0.7 s. */
        const val DISMISSAL_FALLBACK_MILLIS = 700L
    }
}
