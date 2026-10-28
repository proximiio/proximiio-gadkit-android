package io.proximi.gadkit

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.map.BottomGlassBar
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.LocalRouteAccessibility
import io.proximi.gadkit.map.MapIcon
import io.proximi.gadkit.map.RoundGlassButton
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.map.galleries
import io.proximi.gadkit.map.places
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.visit.VisitEvent
import io.proximi.gadkit.visit.VisitMode
import io.proximi.gadkit.visit.VisitScreen
import io.proximi.map.live.JourneyNavigator
import io.proximi.sdk.Proximiio
import kotlinx.coroutines.launch

/**
 * The journey screen on the host app's SDK, as iOS `ProximiMapView` (Nagarro, 2026-10-16: "one UI
 * component for the map view … that plugs into our existing SDK session and event setup"): Map /
 * List toggle, search, the map with floor button, compass and stop card, and the list.
 *
 * The view is a UI component. The host app starts the SDK, attaches the position provider of its
 * wristband session, owns the journey ([ProximiJourney]) and decides when a stop is visited or
 * skipped. The view draws the journey it gets, routes to the first stop still to visit and reports
 * what the visitor does as [ProximiMapEvent]s. It does not start or stop the SDK, does not change
 * the wristband session and reports no geofence or wristband event. It hands its
 * `JourneyNavigator` to `onNavigator`, so the host app reads the map library's journey events and
 * route cues itself.
 *
 * Everything beyond that is in [Option], off by default: the bottom bar, the background, the view's
 * own prompts, the journey logic (visits, order) and the journey notification. [ProximiMap] uses all
 * of them.
 */
public object ProximiMapView {
    /** What the view does beyond drawing the journey and reporting events. iOS `ProximiMapView.Options`. */
    public enum class Option {
        /**
         * The bar at the bottom with Back and the Map / List button. Back shows with `onClose` and
         * calls it, after "Leave the tour here" with [LEAVE_TOUR_PROMPT].
         */
        BOTTOM_BAR,

        /** The library's background image around the map card. Without it the view is transparent. */
        BACKGROUND,

        /**
         * All four prompts: [SKIP_PROMPT], [OFF_ROUTE_PROMPT], [UNCERTAIN_POSITION_PROMPT] and
         * [LEAVE_TOUR_PROMPT]. iOS `.prompts`.
         */
        PROMPTS,

        /** "Skip this artwork?" after a swipe in the list. Without it the view reports [ProximiMapEvent.SkipRequested] only. */
        SKIP_PROMPT,

        /**
         * "Looks like you've left your route" with "Dismiss", [GadSettings.offRouteMeters] off the
         * route and before any routing. After it, with [PROGRESS], a personalised journey keeps the leg
         * where the visitor left it until another artwork is nearer; other journeys are routed again
         * from the visitor. Without it the view reports [ProximiMapEvent.OffRoute] only, and the route
         * follows the visitor who left it.
         */
        OFF_ROUTE_PROMPT,

        /** "Probably at …" when the position is uncertain near the active stop. Without it the view reports [ProximiMapEvent.PositionUncertain]. */
        UNCERTAIN_POSITION_PROMPT,

        /** "Leave the tour here" after Back on the bottom bar during a journey in progress. Without it Back calls `onClose` at once. */
        LEAVE_TOUR_PROMPT,

        /**
         * The view moves the journey on and reports it through `onJourneyChange`: an artwork is
         * visited when the visitor enters its geofence or stays at it out of order, a personalised
         * journey is ordered floor by floor and gallery by gallery, and a stop whose POI is deleted
         * is skipped. Without it only the host app's journey changes the stops, and the view reports
         * no journey.
         */
        PROGRESS,

        /** The ongoing journey notification with the next instruction, iOS `.liveActivity`. */
        JOURNEY_NOTIFICATION,

        /**
         * Journey Complete when the last stop is done. Without it the view reports
         * [ProximiMapEvent.JourneyFinished] and shows nothing (Nagarro, Monday "Journey finished
         * view", 2026-10-26).
         */
        JOURNEY_COMPLETE,
        ;

        public companion object {
            public val ALL: Set<Option> = entries.toSet()
        }
    }
}

/** Whether [prompt] is on: its own option or [ProximiMapView.Option.PROMPTS]. */
internal fun Set<ProximiMapView.Option>.hasPrompt(prompt: ProximiMapView.Option): Boolean =
    prompt in this || ProximiMapView.Option.PROMPTS in this

/**
 * The journey screen of [ProximiMapView] on the host app's SDK.
 *
 * @param sdk the host app's SDK, started, with the position provider of the wristband session
 *   attached.
 * @param journey the journey to show. A journey without stops, or without a stop on the map, shows
 *   the map alone. The view applies every change of the host app to the open screen.
 * @param onJourneyChange the journey with the view's changes, only with
 *   [ProximiMapView.Option.PROGRESS]. Pass it back as [journey].
 * @param options what the view does beyond drawing the journey. None by default. The view reads them
 *   when it opens.
 * @param settings times and distances of the screen. The view reads them when it opens.
 * @param routeAccessibility accessibility needs for routes, on top of the accessible routes button
 *   the visitor can turn on.
 * @param mode Map or List when the view opens.
 * @param onEvent the journey events and the controls the visitor used, on the main thread. Geofence
 *   and wristband events come from the host app's SDK, not from this view.
 * @param onClose Back on the bottom bar, "Leave the tour" and the close button of Journey Complete.
 *   `null` (default): the bottom bar has no Back, so "Leave the tour here" never shows, the close
 *   button of Journey Complete returns to the map, and the system Back stays with the host app.
 * @param destination a place to route the visitor to at once, for example from the audio guide. With
 *   a journey it is a detour, as after "Add a stop" on an amenity, with no card and no prompt; a stop
 *   of the journey still to visit becomes the next stop instead. Without a journey the map shows the
 *   route from the visitor. The view sets it back to `null` through [onDestinationChange] when the
 *   route ends: "Resume your Journey", "Close", another place picked on the map, or
 *   [ProximiMapEvent.DestinationUnavailable]. `null` from the host app ends the route.
 * @param onDestinationChange the destination after the view ended its route (`null`). Pass it back as
 *   [destination].
 * @param isPaused `true` pauses the journey, for example from the audio guide's pause button. The dot
 *   moves and the route stays, but no artwork is visited, no off-route warning shows and the leg is
 *   not routed again. The stop card shows "Journey paused" with "Resume your Journey". `false`
 *   resumes it: the leg is drawn again from the visitor, and a personalised journey with
 *   [ProximiMapView.Option.PROGRESS] is ordered again from the visitor first. A change of the host app
 *   reports no event.
 * @param onPausedChange `false` after "Resume your Journey" on the card of a paused journey, which
 *   also reports [ProximiMapEvent.JourneyResumed]. Pass it back as [isPaused].
 * @param onNavigator called on the main thread with the `JourneyNavigator` that draws the journey,
 *   before the journey starts, and again with a new navigator each time the screen starts again (a
 *   new journey kind, a journey without a stop on the map, the first journey on an empty map). Start
 *   collecting `navigator.events` inside the call, so no event is missed, and cancel the collection
 *   of the previous navigator; set `onCue` and `cueRules` there. The view sets neither. Any other
 *   change of `journey` goes to `update(to)` of the same navigator. Change the journey through
 *   `journey` only: the view does not see calls such as `skip(stopId)` or `markDone(stopId)` on the
 *   navigator. A recreated activity keeps the navigator and does not call it again.
 */
@Composable
public fun ProximiMapView(
    sdk: Proximiio,
    journey: ProximiJourney,
    onJourneyChange: (ProximiJourney) -> Unit,
    modifier: Modifier = Modifier,
    options: Set<ProximiMapView.Option> = emptySet(),
    settings: GadSettings = GadSettings.STANDARD,
    routeAccessibility: RouteAccessibility = RouteAccessibility.NONE,
    mode: VisitMode = VisitMode.MAP,
    onEvent: (ProximiMapEvent) -> Unit = {},
    onClose: (() -> Unit)? = null,
    destination: ProximiDestination? = null,
    onDestinationChange: (ProximiDestination?) -> Unit = {},
    isPaused: Boolean = false,
    onPausedChange: (Boolean) -> Unit = {},
    onNavigator: (JourneyNavigator) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val model: HostedMapModel = viewModel(key = HostedMapModel.KEY)
    val language = LocalConfiguration.current.locales[0]?.toLanguageTag()
    SideEffect {
        model.onEvent = onEvent
        model.onJourneyChange = onJourneyChange
        model.onNavigator = onNavigator
    }
    LaunchedEffect(model) { model.start(context.applicationContext, sdk, settings, options, journey, language) }
    LaunchedEffect(model, journey) { model.journeyChanged(journey) }
    LaunchedEffect(model, language) { model.setLanguage(language) }
    DisposableEffect(model) {
        // A recreated activity shows the same screen again.
        onDispose { if (activity?.isChangingConfigurations != true) model.stop() }
    }
    Box(modifier) {
        val loaded = model.loaded
        if (loaded != null) {
            CompositionLocalProvider(LocalRouteAccessibility provides routeAccessibility) {
                VisitScreen(
                    loaded.venue,
                    loaded.journey,
                    onClose = onClose,
                    kind = loaded.kind,
                    options = loaded.options,
                    mode = mode,
                    incoming = model.incoming,
                    persist = { model.write(it, loaded.generation) },
                    onVisitEvent = model::handle,
                    onNavigator = { model.onNavigator(it) },
                    destination = destination,
                    onDestinationChange = onDestinationChange,
                    isPaused = isPaused,
                    onPausedChange = onPausedChange,
                )
            }
        } else {
            HostedMapLoading(options, onClose)
        }
    }
}

/**
 * The state of a [ProximiMapView]: the venue on the host app's SDK and the journey on screen. A view
 * model, so it survives a recreated activity. Main thread only.
 */
internal class HostedMapModel : ViewModel() {
    data class Loaded(
        val venue: VenueService,
        /** The journey the screen starts with. */
        val journey: Journey,
        val kind: JourneyKind,
        /** The options when the view opened. */
        val options: Set<ProximiMapView.Option>,
        /** Changes when the screen starts again; the journey screen is created again. */
        val generation: Int,
    )

    var loaded by mutableStateOf<Loaded?>(null)
        private set

    /** The host app's journey after a change the open screen applies in place. */
    var incoming by mutableStateOf<IncomingJourney?>(null)
        private set

    var onEvent: (ProximiMapEvent) -> Unit = {}
    var onJourneyChange: (ProximiJourney) -> Unit = {}
    var onNavigator: (JourneyNavigator) -> Unit = {}

    private var venue: VenueService? = null
    private var settings = GadSettings.STANDARD
    private var options: Set<ProximiMapView.Option> = emptySet()
    private var language: String? = null
    private var places: List<VenuePlace> = emptyList()
    private var galleries: List<Gallery> = emptyList()

    /** The host app's journey on screen, matched to the places. */
    private var hosted: HostedJourney? = null

    /** The journey last shown or written. Another value from the host app changes the screen. */
    private var current: ProximiJourney? = null

    /** The host app's latest journey, also while the venue loads. */
    private var latest: ProximiJourney? = null
    private var generation = 0
    private var revision = 0
    private var isStarting = false
    private var isStopped = false

    /**
     * Loads the venue and shows [journey]. After [stop], follows the venue data changes again and
     * shows [journey] on a new screen: the screen of the view that left has ended.
     */
    suspend fun start(context: Context, sdk: Proximiio, settings: GadSettings, options: Set<ProximiMapView.Option>, journey: ProximiJourney, language: String?) {
        venue?.let { venue ->
            if (isStopped) {
                isStopped = false
                // A new view reads its options and settings when it opens.
                this.options = options
                this.settings = settings
                venue.settings = settings
                venue.data.start()
                latest = journey
                show(journey)
            }
            return
        }
        if (isStarting) return
        isStarting = true
        this.settings = settings
        this.options = options
        this.language = language
        if (latest == null) latest = journey
        val venue = VenueService.hosted(context, sdk, settings)
        this.venue = venue
        loadPlaces()
        venue.data.onChange = { viewModelScope.launch { loadPlaces() } }
        FieldLog.log("hosted map: ready · ${places.size} places · options ${options.joinToString { it.name }}")
        show(latest ?: journey)
    }

    private suspend fun loadPlaces() {
        val venue = venue ?: return
        places = venue.places(language)
        galleries = venue.galleries(language)
    }

    /** The app's language: the places follow it. */
    suspend fun setLanguage(tag: String?) {
        if (tag == language) return
        language = tag
        loadPlaces()
    }

    /** Starts the screen with [journey]. */
    private fun show(journey: ProximiJourney) {
        current = journey
        val venue = venue ?: return
        val matched = HostedJourney.of(journey, places, galleries)
        FieldLog.log("hosted map: start · ${journey.kind.rawValue} · ${matched.journey.stops.size} of ${journey.stops.size} stops on the map")
        hosted = matched
        incoming = null
        generation += 1
        loaded = Loaded(venue, matched.journey, journey.kind, options, generation)
    }

    /**
     * The host app's journey changed. The open screen applies a value the view did not write in
     * place; one it cannot apply starts the screen again.
     */
    fun journeyChanged(journey: ProximiJourney) {
        latest = journey
        if (venue == null || journey == current) return
        current = journey
        val matched = HostedJourney.of(journey, places, galleries)
        val hosted = hosted
        val loaded = loaded
        if (hosted == null || loaded == null || !matched.canReplace(hosted, journey.kind, loaded.kind)) {
            show(journey)
            return
        }
        FieldLog.log("hosted map: journey applied in place · ${matched.journey.stops.size} stops")
        this.hosted = matched
        revision += 1
        incoming = IncomingJourney(matched.journey, revision)
    }

    /**
     * Reports the order and states of the navigator's journey as the host app's journey, with
     * [ProximiMapView.Option.PROGRESS] only, and only from the screen of the current [generation]: a
     * screen that started again leaves the controller of the old one running until its work ends.
     * `null` (after Journey Complete) changes nothing: the journey already holds the last states. A
     * new order of the stops sends [ProximiMapEvent.StopOrderChanged] after the write.
     */
    fun write(journey: Journey?, generation: Int) {
        if (ProximiMapView.Option.PROGRESS !in options || generation != this.generation || journey == null) return
        val hosted = hosted ?: return
        val shown = latest ?: return
        val updated = hosted.hostJourney(journey, shown.kind)
        if (updated == shown) return
        current = updated
        latest = updated
        onJourneyChange(updated)
        if (isReordered(shown, updated)) onEvent(ProximiMapEvent.StopOrderChanged(updated))
    }

    fun handle(event: VisitEvent) {
        FieldLog.log("hosted map event: $event")
        ProximiMap.event(event, places)?.let(onEvent)
    }

    /** Ends the venue data downloads. */
    fun stop() {
        isStopped = true
        venue?.stop()
    }

    override fun onCleared() {
        venue?.stop()
    }

    companion object {
        const val KEY = "io.proximi.gadkit.hostedMap"

        /**
         * `true` when the stop ids of [updated] are in another order than those of [previous]. A
         * change of the states alone is no new order. Pure; covered by tests.
         */
        fun isReordered(previous: ProximiJourney, updated: ProximiJourney): Boolean =
            previous.stops.map { it.id } != updated.stops.map { it.id }
    }
}

/** While the venue loads: a spinner, with the background and Back of [options]. */
@Composable
private fun HostedMapLoading(options: Set<ProximiMapView.Option>, onClose: (() -> Unit)?) {
    Box(Modifier.fillMaxSize()) {
        if (ProximiMapView.Option.BACKGROUND in options) {
            Image(painterResource(R.drawable.background_map), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier.fillMaxSize().padding(bottom = maxOf(navigationBottom - 14.dp, 4.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator(color = Palette.ink)
            Spacer(Modifier.weight(1f))
            if (ProximiMapView.Option.BOTTOM_BAR in options && onClose != null) {
                BottomGlassBar(
                    leading = {
                        RoundGlassButton(onClose, stringResource(R.string.back)) { MapIcon(R.drawable.ic_chevron_backward, size = 18) }
                    },
                    trailing = {},
                )
            }
        }
    }
}
