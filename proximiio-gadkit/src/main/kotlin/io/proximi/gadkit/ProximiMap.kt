package io.proximi.gadkit

import android.content.Context
import io.proximi.gadkit.map.GeofenceNotifier
import io.proximi.gadkit.map.LocalNotice
import io.proximi.gadkit.map.LocalNotices
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.map.galleries
import io.proximi.gadkit.map.isArtwork
import io.proximi.gadkit.map.places
import io.proximi.gadkit.ui.resolve
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.StopState
import io.proximi.gadkit.visit.VisitEvent
import io.proximi.gadkit.visit.VisitMeta
import io.proximi.gadkit.visit.VisitPlan
import io.proximi.gadkit.visit.VisitStore
import io.proximi.gadkit.visit.toCore
import io.proximi.gadkit.wristband.BindingState
import io.proximi.gadkit.wristband.WristbandCopy
import io.proximi.gadkit.wristband.WristbandPairingFlow
import io.proximi.gadkit.wristband.WristbandSession
import io.proximi.gadkit.wristband.connect
import io.proximi.gadkit.wristband.toCore
import io.proximi.map.core.JourneyEvent
import io.proximi.sdk.Proximiio
import io.proximi.sdk.blueiot.binding.BlueiotBindingState
import io.proximi.sdk.core.model.ProximiioGeofence
import io.proximi.sdk.geofenceEvents
import io.proximi.sdk.geofencing.GeofenceEvent
import io.proximi.sdk.positions
import io.proximi.sdk.refreshPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * The library's one entry point for the host app, as iOS `ProximiMap` (Nagarro, 2026-10-16: "a
 * single exposed wrapper component that encapsulates all internal UI components and logic").
 *
 * The host app creates one `ProximiMap` per process, calls [start], composes [ProximiMapHost] at
 * the root of its UI, opens screens with [open] and receives [events]. `ProximiMap` owns the SDK,
 * the wristband session, the geofence notifications, the stored journey and every screen and sheet
 * of the library. The host app scans the wristband's QR code itself and passes the code to
 * [connectWristband].
 *
 * Call the functions on the main thread. The state flows and [events] are updated on the main
 * thread.
 *
 * @param context any context; the library keeps the application context.
 * @param scope the scope of the work that runs for the life of the object: the wristband session,
 *   the geofence events and notifications. A process-wide scope, for example the application's.
 */
public class ProximiMap(
    context: Context,
    public val configuration: GadConfiguration,
    scope: CoroutineScope,
) {
    /** The screens and sheets [open] shows. */
    public enum class Screen {
        /** The museum map with the gallery legend, search and routes to amenities. */
        MUSEUM_MAP,

        /**
         * Journey ready (02.10A): the journey's title, "Start the Journey" at the museum, "Go to
         * Museum" away from it. "Start the Journey" opens [JOURNEY].
         */
        JOURNEY_READY,

        /** The journey: map and list, guidance from stop to stop, Journey Complete. */
        JOURNEY,

        /** The stop picker for a personalised journey ("Plan your visit"). "Start" stores the journey and opens [JOURNEY_READY]. */
        PLAN_JOURNEY,

        /** Manage journey (02.11): edit the stops, craft a new journey, delete the journey. */
        MANAGE_JOURNEY,
    }

    /** The state of [start]. */
    public sealed interface Status {
        public data object Idle : Status

        public data object Starting : Status

        public data object Ready : Status

        public data class Failed(public val error: ProximiMapException) : Status
    }

    private val context = context.applicationContext

    /** Main-thread work for the life of [scope]. */
    private val mainScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)

    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    private val mutableJourney = MutableStateFlow<JourneySummary?>(null)
    private val mutableEvents = MutableSharedFlow<ProximiMapEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The state of [start]: for a splash or a retry screen. */
    public val status: StateFlow<Status> = mutableStatus.asStateFlow()

    /** The stored journey, or `null`. It survives app launches. */
    public val journey: StateFlow<JourneySummary?> = mutableJourney.asStateFlow()

    /**
     * The visitor's accessibility needs for routes, on top of the accessible routes button the
     * visitor can turn on. Set [MutableStateFlow.value]; a change re-routes the route on screen.
     */
    public val routeAccessibility: MutableStateFlow<RouteAccessibility> = MutableStateFlow(RouteAccessibility.NONE)

    /**
     * Every [ProximiMapEvent] from the moment of collection. A collector that falls more than 100
     * events behind loses the oldest ones. Events sent while nothing collects are dropped.
     */
    public val events: Flow<ProximiMapEvent> = mutableEvents.asSharedFlow()

    internal val session = WristbandSession(this.context, configuration, mainScope)
    internal val arrival = ArrivalDetector(this.context)
    internal val presenter = ProximiMapPresenter(mainScope, ::emit, onDismissed = ::screenDismissed)

    /** `true` once the visitor is at the museum ([ProximiMapEvent.ArrivedAtMuseum]). */
    public val isAtMuseum: StateFlow<Boolean> = arrival.isAtMuseum

    /**
     * `true` when the visitor did not allow location for the app. The library needs it for the
     * arrival and for a wristband take-over; the host app can show its own "Location is required"
     * screen. It stays `false` until the location dialog of [ProximiMapHost] has been answered.
     */
    public val isLocationDenied: StateFlow<Boolean> = arrival.isDenied

    private val mutableWristband = MutableStateFlow(wristbandStatus(session.state.value.toCore(), ::text))

    /** The phone's wristband session. */
    public val wristband: StateFlow<WristbandStatus> = mutableWristband.asStateFlow()

    /** The screen or sheet on screen, or `null`. */
    public val screen: StateFlow<Screen?> = presenter.screen

    /** The stored journey, read from the disk off the main thread once `storeLoad` has finished. */
    private var store: VisitStore? = null
    private val storeLoad: Deferred<VisitStore> = mainScope.async {
        withContext(Dispatchers.IO) { VisitStore.of(this@ProximiMap.context) }.also { loaded ->
            store = loaded
            mutableJourney.value = summary(loaded.load(), loaded.loadMeta(), ::standardMeta)
        }
    }

    internal var venue: VenueService? = null
        private set

    private val mutablePlaces = MutableStateFlow<List<VenuePlace>>(emptyList())

    /** The venue's places in the app's language, for the stop picker. */
    internal val places: StateFlow<List<VenuePlace>> = mutablePlaces.asStateFlow()
    private var galleries: List<Gallery> = emptyList()
    private var language: String? = context.resources.configuration.locales[0]?.toLanguageTag()

    private var startJob: Deferred<Unit>? = null
    private var isSessionRunning = false
    private var lastWristband: WristbandStatus? = null

    /** The debug launch options, from the activity that composes [ProximiMapHost]. */
    private val launchOptions = CompletableDeferred<DebugLaunch.Options?>()

    /** `true` once [ProximiMapHost] has shown the location dialog in this process. */
    internal var hasAskedLocation: Boolean = false

    /** `gad.hostedMap`: the journey of the [ProximiMapView] the debug launch opens. */
    internal val hostedJourney = MutableStateFlow(ProximiJourney(stops = emptyList()))

    init {
        arrival.onArrival = {
            FieldLog.log("map: at the museum")
            emit(ProximiMapEvent.ArrivedAtMuseum)
        }
    }

    // Start

    /**
     * Starts the SDK, the wristband session and the geofence notifications, and loads the venue.
     * A second call while starting waits for the first; after a failure it starts again. Call it at
     * launch: the map style and the venue data are then loaded before the first map screen.
     *
     * The location dialog comes from [ProximiMapHost]; the start does not wait for it. The SDK starts
     * its positioning service once location is allowed.
     *
     * @throws ProximiMapException.StartFailed
     */
    public suspend fun start() {
        if (status.value == Status.Ready) return
        startJob?.let { return it.await() }
        val job = mainScope.async { performStart() }
        startJob = job
        try {
            job.await()
        } catch (error: Throwable) {
            if (startJob === job) startJob = null
            throw error
        }
    }

    private suspend fun performStart() {
        mutableStatus.value = Status.Starting
        runSession()
        try {
            val store = storeLoad.await()
            val venue = VenueService.start(context, configuration, session.binding)
            this.venue = venue
            session.forwardPrivacy(venue.sdk)
            // A location answer that came during the start reaches the SDK here.
            venue.sdk.refreshPermissions()
            loadPlaces(venue)
            reconcileStoredJourney(store)
            arrival.check(places.value)
            venue.data.onChange = { mainScope.launch { venueDataChanged(venue) } }
            mainScope.launch {
                // One position from the wristband or playback means the visitor is inside.
                if (venue.sdk.positions().firstOrNull() != null) arrival.positionReceived()
            }
            mainScope.launch { venue.sdk.geofenceEvents().collect { event -> event(event)?.let(::emit) } }
            if (configuration.settings.postsGeofenceNotifications && configuration.settings.notifiesGeofenceEvents) {
                mainScope.launch { GeofenceNotifier(context).run(venue.sdk) }
            }
            mutableJourney.value = summary(store.load(), store.loadMeta(), ::standardMeta)
            mutableStatus.value = Status.Ready
            FieldLog.log("map: ready · ${places.value.size} places")
            if (venue.isDebugBuild) mainScope.launch { runDebugLaunch(venue, launchOptions.await()) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FieldLog.log("map: start failed: $error")
            val failure = ProximiMapException.StartFailed(text(R.string.i_couldn_t_get_things_started_check), error.toString())
            mutableStatus.value = Status.Failed(failure)
            throw failure
        }
    }

    private suspend fun loadPlaces(venue: VenueService) {
        val language = language
        mutablePlaces.value = venue.places(language)
        galleries = venue.galleries(language)
    }

    /** The venue data changed on the backend. */
    private suspend fun venueDataChanged(venue: VenueService) {
        loadPlaces(venue)
        store?.let(::reconcileStoredJourney)
    }

    /**
     * Updates the stored journey with the current places ([VisitPlan.reconciled]). While the journey
     * screen is open, the screen updates its journey and stores it.
     */
    private fun reconcileStoredJourney(store: VisitStore) {
        if (presenter.screen.value == Screen.JOURNEY) return
        val stored = store.load() ?: return
        val journey = VisitPlan.reconciled(stored, places.value, galleries)
        if (journey == stored) return
        FieldLog.log("map: stored journey updated · ${stored.stops.size} → ${journey.stops.size} stops")
        store.save(journey)
        if (journey.stops.isEmpty()) store.saveMeta(null)
        refreshJourney()
    }

    /** A screen has gone: the stored journey follows the places, then the host app reads it again. */
    private fun screenDismissed() {
        store?.let(::reconcileStoredJourney)
        refreshJourney()
    }

    /** The app's language, from [ProximiMapHost]: the places and the stop picker follow it. */
    internal fun setLanguage(tag: String?) {
        if (tag == language) return
        language = tag
        val venue = venue ?: return
        mainScope.launch { loadPlaces(venue) }
    }

    /**
     * Follows the wristband session for the life of the object: confirms a session stored by the
     * SDK with the relay-api and follows the binding state.
     */
    private fun runSession() {
        if (isSessionRunning) return
        isSessionRunning = true
        session.run()
        mainScope.launch { session.state.collect { wristbandStateChanged() } }
    }

    // Wristband

    /**
     * Connects the phone to the wristband with [code]: the scanned QR payload or the code printed on
     * the wristband. The relay-api resolves every label format. When the relay-api needs the phone's
     * location for a take-over, the system location dialog comes first ([ProximiMapHost] shows it).
     * Returns when the session is active; it replaces any current one.
     *
     * @throws ProximiMapException.Wristband with the text for the visitor.
     */
    public suspend fun connectWristband(code: String) {
        runSession()
        try {
            session.connect(code)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FieldLog.log("map: wristband not connected: $error")
            throw ProximiMapException.Wristband(text(WristbandCopy.message(error)))
        }
    }

    /**
     * Ends the wristband session on the relay-api, for example when the visitor returns the
     * wristband. [wristband] becomes [WristbandStatus.NotConnected].
     *
     * @throws ProximiMapException.Wristband when the relay-api cannot be reached; the session is kept.
     */
    public suspend fun endVisit() {
        try {
            session.end()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ProximiMapException.Wristband(text(WristbandCopy.message(error)))
        }
    }

    private fun wristbandStateChanged() {
        val status = wristbandStatus(session.state.value.toCore(), ::text)
        mutableWristband.value = status
        if (status == lastWristband) return
        lastWristband = status
        emit(ProximiMapEvent.WristbandChanged(status))
    }

    /**
     * The wristband pairing flow of the Proximi GAD app (`WristbandPairingScreen`). Host apps scan
     * the QR code themselves and call [connectWristband].
     */
    @ProximiGadAppApi
    public fun makeWristbandPairing(): WristbandPairingFlow {
        runSession()
        return WristbandPairingFlow.of(session, mainScope)
    }

    /**
     * The wristband binding's state now, then every change. The Proximi GAD app reads the relay
     * signal from it for a test notification in debug and staging builds.
     */
    @ProximiGadAppApi
    public fun wristbandBindingStates(): Flow<BlueiotBindingState> = session.binding.stateChanges()

    /**
     * Posts a notification on the library's channel `alerts`, as the geofence notifications. The
     * Proximi GAD app posts its "next stop nearby" notification and the test notifications of debug
     * and staging builds with it. A notice with the same [tag] replaces the earlier one. Nothing
     * without the notification permission.
     */
    @ProximiGadAppApi
    public fun postNotification(tag: String, title: String, body: String, group: String) {
        LocalNotices.post(context, LocalNotice(tag, title, UiText.Verbatim(body), group), APP_NOTIFICATION_ID)
    }

    /**
     * The started SDK. `null` until [start] succeeds. The Proximi GAD app logs the SDK's events from
     * it in debug and staging builds.
     */
    @ProximiGadAppApi
    public val sdk: Proximiio? get() = venue?.sdk

    // Journey

    /**
     * Stores a journey; it replaces the stored one. Opens nothing: open [Screen.JOURNEY_READY] or
     * [Screen.JOURNEY].
     *
     * @return the stored journey.
     * @throws ProximiMapException.NotStarted before [start] has finished.
     * @throws ProximiMapException.NoKnownArtworks when no artwork of [plan] is a POI of the venue.
     */
    public fun setJourney(plan: JourneyPlan): JourneySummary {
        val store = store
        if (status.value != Status.Ready || store == null) throw ProximiMapException.NotStarted(text(R.string.i_couldn_t_get_things_started_check))
        val ids = poiIds(plan.artworks, places.value)
        if (ids.isEmpty()) throw ProximiMapException.NoKnownArtworks(text(R.string.none_of_these_artworks_is_on_the))
        val journey = VisitPlan.journey(ids, places.value, galleries)
        val meta = VisitMeta(
            title = plan.title,
            summary = plan.summary ?: stopsText(journey.stops.size),
            kind = plan.kind,
        )
        saveJourney(store, journey, meta)
        FieldLog.log("map: journey ${plan.kind.rawValue} · ${ids.size} of ${plan.artworks.size} artworks")
        return checkNotNull(summary(journey, meta, ::standardMeta))
    }

    /** Deletes the stored journey. */
    public fun deleteJourney() {
        val store = store
        if (store != null) {
            saveJourney(store, null, null)
        } else {
            mainScope.launch { saveJourney(storeLoad.await(), null, null) }
        }
    }

    private fun saveJourney(store: VisitStore, journey: Journey?, meta: VisitMeta?) {
        store.save(journey)
        store.saveMeta(meta)
        refreshJourney()
    }

    /** Reads the stored journey again; the journey screen updates it as the visitor walks. */
    private fun refreshJourney() {
        val store = store ?: return
        val summary = summary(store.load(), store.loadMeta(), ::standardMeta)
        if (summary == mutableJourney.value) return
        mutableJourney.value = summary
        emit(ProximiMapEvent.JourneyChanged(summary))
    }

    // Screens

    /**
     * Shows [screen] in [ProximiMapHost]. A screen already on screen closes first. Nothing opens
     * before [start] has finished, and [Screen.JOURNEY_READY], [Screen.JOURNEY] and
     * [Screen.MANAGE_JOURNEY] open only with a stored journey.
     */
    public fun open(screen: Screen) {
        val store = store
        val journey = store?.load()
        if (!canOpen(screen, status.value, hasJourney = journey != null)) {
            FieldLog.log("map: open ${screen.name} ${if (status.value != Status.Ready) "before the start" else "without a journey"}")
            return
        }
        FieldLog.log("map: open ${screen.name} · on screen ${this.screen.value?.name ?: "-"}")
        presenter.replace { show(screen) }
    }

    /** Closes the screen or sheet on screen. */
    public fun close() {
        presenter.close()
    }

    /** Shows [screen] with the stored journey as it is now. */
    private fun show(screen: Screen) {
        val store = store ?: return
        val journey = store.load()
        val presentation = when (screen) {
            Screen.MUSEUM_MAP -> Presentation.MuseumMap
            Screen.PLAN_JOURNEY -> Presentation.PlanJourney
            Screen.JOURNEY_READY -> journey?.let { Presentation.JourneyReady(it, storedMeta(store, it)) }
            Screen.JOURNEY -> journey?.let { Presentation.Visit(it, storedMeta(store, it).kind) }
            Screen.MANAGE_JOURNEY -> journey?.let { Presentation.ManageJourney(storedMeta(store, it)) }
        } ?: return
        presenter.show(presentation)
    }

    private fun storedMeta(store: VisitStore, journey: Journey): VisitMeta =
        store.loadMeta() ?: standardMeta(VisitPlan.artworks(journey).size, JourneyKind.PERSONALISED)

    // Called by ProximiMapHost.

    /**
     * Journey ready → "Start the Journey": the stored journey, since the venue data can change while
     * Journey ready is open. Without one the screen closes.
     */
    internal fun visitStarted(ready: Presentation.JourneyReady) {
        val stored = store?.load() ?: return presenter.hide()
        presenter.swap(Presentation.Visit(stored, ready.meta.kind))
    }

    /** "Start" in the stop picker: stores the personalised journey and opens Journey ready. */
    internal fun planned(poiIds: List<String>) {
        val store = store ?: return
        val journey = VisitPlan.journey(poiIds, places.value, galleries)
        val meta = standardMeta(journey.stops.size, JourneyKind.PERSONALISED)
        saveJourney(store, journey, meta)
        presenter.replace { presenter.show(Presentation.JourneyReady(journey, meta)) }
    }

    /** Manage journey → "Edit stops": the stop picker, or the host app's tour choice for a curated tour. */
    internal fun editJourney() {
        if (journey.value?.kind == JourneyKind.CURATED) {
            presenter.replace { emit(ProximiMapEvent.CuratedTourChangeRequested) }
        } else {
            presenter.replace { show(Screen.PLAN_JOURNEY) }
        }
    }

    /** Manage journey → "Craft a new journey". */
    internal fun craftNewJourney() {
        presenter.replace { emit(ProximiMapEvent.CraftNewJourneyRequested) }
    }

    /** Manage journey → "Delete this journey". */
    internal fun journeyDeleted() {
        deleteJourney()
        presenter.hide()
    }

    /** The debug launch options of the activity; the first call counts. */
    internal fun launchOptionsRead(options: DebugLaunch.Options?) {
        if (!launchOptions.complete(options)) return
        if (options != null && options.routeAccessibility != RouteAccessibility.NONE) routeAccessibility.value = options.routeAccessibility
    }

    /** The location dialog was answered, or location was already allowed; or the host screen resumed. */
    internal fun locationAuthorizationChanged(answered: Boolean) {
        if (arrival.refreshAuthorization(answered)) {
            val venue = venue ?: return
            mainScope.launch { venue.sdk.refreshPermissions() }
        }
    }

    // Events

    internal fun handle(event: VisitEvent) {
        FieldLog.log("visit event: $event")
        event(event, places.value)?.let(::emit)
    }

    private fun emit(event: ProximiMapEvent) {
        mutableEvents.tryEmit(event)
    }

    // Debug launch

    /**
     * Debuggable host builds: runs the debug launch extras after the start: journey playback, a
     * wristband bind, `gad.openMap` and `gad.visit`.
     */
    private suspend fun runDebugLaunch(venue: VenueService, options: DebugLaunch.Options?) {
        options ?: return
        DebugLaunch.run(options, venue, places.value, session)
        // With `gad.hostedMap` the museum map is `ProximiExploreView`.
        if (options.openMap && options.hostedMap) return presenter.replace { presenter.show(Presentation.HostedExplore) }
        if (options.openMap) open(Screen.MUSEUM_MAP)
        val ids = options.visitStops.mapNotNull { VenuePlace.matching(it, places.value).firstOrNull()?.id }
        if (options.hostedMap) {
            // Without `gad.visit` the view shows the map alone.
            DebugLaunch.log("hosted map: ${ids.size} stops")
            hostedJourney.value = ProximiJourney.ofArtworks(ids, options.visitKind)
            presenter.replace { presenter.show(Presentation.HostedMap) }
            return
        }
        if (ids.isEmpty()) return
        val store = storeLoad.await()
        DebugLaunch.log("visit: ${ids.size} stops")
        val journey = VisitPlan.journey(ids, places.value, galleries)
        saveJourney(store, journey, standardMeta(journey.stops.size, options.visitKind))
        open(Screen.JOURNEY)
    }

    // Text

    private fun text(id: Int): String = context.getString(id)

    private fun text(text: UiText): String = text.resolve(context)

    private fun stopsText(count: Int): String = context.resources.getQuantityString(R.plurals.n_stops, count, count)

    /** iOS `VisitMeta.standard(stopCount:kind:)`: "Your journey", "%d stops". */
    private fun standardMeta(stopCount: Int, kind: JourneyKind): VisitMeta =
        VisitMeta(title = text(R.string.your_journey_2), summary = stopsText(stopCount), kind = kind)

    internal companion object {
        private const val EVENT_BUFFER = 100

        /** The notification id of [postNotification]; the tag tells the notices apart. */
        private const val APP_NOTIFICATION_ID = 2_003

        /** Whether [open] shows [screen]. Pure; covered by tests. */
        fun canOpen(screen: Screen, status: Status, hasJourney: Boolean): Boolean {
            if (status != Status.Ready) return false
            return when (screen) {
                Screen.JOURNEY_READY, Screen.JOURNEY, Screen.MANAGE_JOURNEY -> hasJourney
                Screen.MUSEUM_MAP, Screen.PLAN_JOURNEY -> true
            }
        }

        /**
         * The POI ids of [artworks]: each id is a POI id or an artwork's collection id (`remote_id`).
         * Ids that match no place are left out. Pure; covered by tests.
         */
        fun poiIds(artworks: List<String>, places: List<VenuePlace>): List<String> {
            val byId = places.mapTo(HashSet()) { it.id }
            val byRemoteId = HashMap<String, String>()
            for (place in places) place.remoteId?.let { byRemoteId.putIfAbsent(it, place.id) }
            return artworks.mapNotNull { id -> if (id in byId) id else byRemoteId[id] }
        }

        /**
         * The summary of a stored journey, or `null` without one. [standard] is the meta of a journey
         * stored without one. Pure; covered by tests.
         */
        fun summary(journey: Journey?, meta: VisitMeta?, standard: (stopCount: Int, kind: JourneyKind) -> VisitMeta): JourneySummary? {
            journey ?: return null
            val artworks = VisitPlan.artworks(journey).size
            val shown = meta ?: standard(artworks, JourneyKind.PERSONALISED)
            return JourneySummary(
                title = shown.title,
                summary = shown.summary,
                kind = shown.kind,
                stopCount = artworks,
                remainingStopCount = VisitPlan.remainingStopCount(journey),
                isStarted = journey.stops.any { it.state != StopState.PENDING },
            )
        }

        /** The wristband status for a binding state. [text] resolves the notice. Pure; covered by tests. */
        fun wristbandStatus(state: BindingState, text: (UiText) -> String): WristbandStatus = when (state) {
            is BindingState.Active -> WristbandStatus.Connected
            BindingState.Binding -> WristbandStatus.Connecting
            is BindingState.Ended -> WristbandStatus.NotConnected(notice = text(WristbandCopy.notice(state.reason)))
            BindingState.Unbound -> WristbandStatus.NotConnected(notice = null)
        }

        /** The host event for a geofence enter or exit of the SDK, or `null` for another event. Pure; covered by tests. */
        fun event(event: GeofenceEvent): ProximiMapEvent? {
            fun geofence(geofence: ProximiioGeofence) = ProximiGeofence(
                id = geofence.id,
                name = geofence.name,
                type = geofence.metadata?.get("geofence_type")?.stringValue,
                artworkId = if (geofence.isArtwork) geofence.remoteId else null,
            )
            return when (event) {
                is GeofenceEvent.Entered -> ProximiMapEvent.GeofenceEntered(geofence(event.geofence))
                is GeofenceEvent.Exited -> ProximiMapEvent.GeofenceExited(geofence(event.geofence))
                else -> null
            }
        }

        /**
         * The host event for a [VisitEvent], or `null` for a navigator event the host app does not
         * get. The navigator's own `StopSkipped` is not repeated: the visitor's confirmation sends it.
         * Pure; covered by tests.
         */
        fun event(event: VisitEvent, places: List<VenuePlace>): ProximiMapEvent? {
            fun stop(stop: JourneyStop): ProximiStop {
                val id = stop.poiId ?: stop.id
                return ProximiStop(
                    id = id,
                    title = stop.title,
                    artworkId = places.firstOrNull { it.id == id }?.remoteId,
                    level = stop.level?.roundedInt(),
                )
            }
            fun place(place: VenuePlace) = ProximiStop(
                id = place.id,
                title = place.title,
                artworkId = if (place.isArtwork) place.remoteId else null,
                level = place.level.roundedInt(),
            )
            return when (event) {
                is VisitEvent.ModeChanged -> ProximiMapEvent.ModeChanged(event.mode)
                is VisitEvent.SkipRequested -> ProximiMapEvent.SkipRequested(stop(event.stop))
                is VisitEvent.StopSkipped -> ProximiMapEvent.StopSkipped(stop(event.stop))
                is VisitEvent.SkipCancelled -> ProximiMapEvent.SkipCancelled(stop(event.stop))
                is VisitEvent.AmenitySelected -> ProximiMapEvent.AmenitySelected(place(event.place))
                is VisitEvent.DetourStarted -> ProximiMapEvent.DetourStarted(place(event.place))
                VisitEvent.JourneyResumed -> ProximiMapEvent.JourneyResumed
                VisitEvent.Recentred -> ProximiMapEvent.Recentred
                is VisitEvent.AccessibleRoutesChanged -> ProximiMapEvent.AccessibleRoutesChanged(event.isOn)
                VisitEvent.ExitRequested -> ProximiMapEvent.ExitRequested
                VisitEvent.ExitConfirmed -> ProximiMapEvent.ExitConfirmed
                VisitEvent.ExitCancelled -> ProximiMapEvent.ExitCancelled
                is VisitEvent.StopNearby -> ProximiMapEvent.StopNearby(stop(event.stop))
                is VisitEvent.PositionUncertain -> ProximiMapEvent.PositionUncertain(stop(event.stop))
                is VisitEvent.StopPlaceDeleted -> ProximiMapEvent.StopPlaceDeleted(stop(event.stop))
                is VisitEvent.ArtworkReached -> ProximiMapEvent.ArtworkReached(stop(event.stop))
                is VisitEvent.ArtworkVisitedOutOfOrder -> ProximiMapEvent.ArtworkVisitedOutOfOrder(stop(event.stop))
                is VisitEvent.TrackingChanged -> ProximiMapEvent.TrackingChanged(event.health)
                is VisitEvent.FloorWithoutPositioningEntered -> ProximiMapEvent.FloorWithoutPositioningEntered(event.level)
                is VisitEvent.FloorWithoutPositioningLeft -> ProximiMapEvent.FloorWithoutPositioningLeft(event.level)
                is VisitEvent.Journey -> when (val journeyEvent = event.event) {
                    is JourneyEvent.LegChanged -> ProximiMapEvent.NextStop(stop(journeyEvent.to.toCore()))
                    is JourneyEvent.DetourEnded -> ProximiMapEvent.DetourEnded(stop(journeyEvent.stop.toCore()), journeyEvent.completed)
                    is JourneyEvent.OffRouteTooLong -> ProximiMapEvent.OffRoute(journeyEvent.duration.seconds)
                    JourneyEvent.ReturnedToRoute -> ProximiMapEvent.BackOnRoute
                    JourneyEvent.JourneyFinished -> ProximiMapEvent.JourneyFinished
                    else -> null
                }
            }
        }
    }
}
