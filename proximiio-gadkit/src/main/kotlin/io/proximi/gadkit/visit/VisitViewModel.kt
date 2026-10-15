package io.proximi.gadkit.visit

import io.proximi.gadkit.UiText
import io.proximi.gadkit.map.VenueLevel
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.map.JourneyMapContent
import io.proximi.gadkit.map.MapLanguage
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.wristband.TrackingHealth
import io.proximi.gadkit.visit.GalleryFirst
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.LocationAmbiguity
import io.proximi.gadkit.visit.LocationChoice
import io.proximi.gadkit.visit.StopKind
import io.proximi.gadkit.visit.StopState
import io.proximi.gadkit.visit.VisitActivity
import io.proximi.gadkit.visit.VisitCardState
import io.proximi.gadkit.visit.VisitPlan
import io.proximi.gadkit.wristband.BindingState
import io.proximi.gadkit.DebugLaunch
import io.proximi.gadkit.map.JourneyMapLayers
import io.proximi.gadkit.map.MapStyle
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.map.gadIcon
import io.proximi.gadkit.map.toCore
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.map.artworkAreas
import io.proximi.gadkit.map.galleries
import io.proximi.gadkit.map.isArtwork
import io.proximi.gadkit.map.isGallery
import io.proximi.gadkit.map.places
import io.proximi.gadkit.wristband.TrackingMonitor
import io.proximi.gadkit.wristband.WristbandPairingFlow
import io.proximi.gadkit.wristband.WristbandSession
import io.proximi.gadkit.map.LocalNotices
import io.proximi.map.core.RouteGuidance
import io.proximi.sdk.wayfinding.NoRouteReason
import io.proximi.map.core.VenuePosition
import io.proximi.map.core.JourneyStop as MapJourneyStop
import io.proximi.map.live.ProximiioMapSession
import io.proximi.sdk.geofenceEvents
import io.proximi.sdk.geofencing.GeofenceEvent
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * State and actions of the journey screen, as the `@State` of iOS `VisitView`. Owned by
 * [VisitHolder] for as long as `VisitScreen` is on screen, so it survives an activity recreation
 * (a language change); [close] when the screen leaves. Main thread only.
 *
 * It owns the map session (the map view attaches to it), the [VisitController], the
 * [TrackingMonitor], the journey map layers and the visit notification.
 *
 * @param onClose the host app's `onClose` of `VisitScreen` (iOS `onClose`).
 * @param onEvent the host app's `onVisitEvent` of `VisitScreen` (iOS `onVisitEvent`).
 */
internal class VisitViewModel(
    private val application: Application,
    val venue: VenueService,
    val wristband: WristbandSession,
    journey: Journey,
    parentScope: CoroutineScope,
    private val debug: DebugLaunch.Options?,
    /** The accessibility needs when the screen opens; [setAccessibility] changes them. */
    accessibility: RouteAccessibility,
    private val onClose: () -> Unit,
    /** The host app's `onVisitEvent` of `VisitScreen`. Called on the main thread. */
    private val onEvent: (VisitEvent) -> Unit = {},
) {
    /** What the navigator reports, read on each change of its journey. */
    data class Progress(
        val journey: Journey,
        val activeStop: JourneyStop?,
        val hasArrived: Boolean,
        val isFinished: Boolean,
        val isDetour: Boolean,
    )

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var language = MapLanguage.forAppLanguage(application.resources.configuration.locales[0]?.language)

    val session = ProximiioMapSession(venue.sdk, application, MapStyle.options(language, routeMarkers = false))
    private val store = VisitStore.of(application)
    val controller = VisitController(
        session,
        journey,
        store,
        scope,
        accessibility,
        amenityIconName = { place ->
            AmenityKind.entries.firstOrNull { it.matches(place) }?.let { application.resources.getResourceEntryName(it.gadIcon.glyph) }
        },
        autoAdvance = debug?.visitAutoAdvance == true,
    )
    val monitor = TrackingMonitor(venue.sdk, scope)
    val layers = JourneyMapLayers()
    private val notification = VisitNotification(application)

    /** "The next stop of your journey is less than 5 m away", once per stop in this visit. */
    private val stopNearby = StopNearbyNotifier { notice -> LocalNotices.post(application, notice, StopNearbyNotifier.NOTIFICATION_ID) }

    var progress by mutableStateOf(readProgress())
        private set
    var guidance by mutableStateOf<RouteGuidance?>(null)
        private set

    /** Why the navigator has no route to a stop, by stop id. */
    private var noRouteReasons by mutableStateOf<Map<String, NoRouteReason>>(emptyMap())

    /**
     * `true` when no route with the visitor's accessibility needs reaches the active stop. The
     * stop keeps its place without a route line; in a personalised journey the visitor can skip it.
     */
    val hasNoAccessibleRoute: Boolean
        get() = progress.activeStop?.let { noRouteReasons[it.id] is NoRouteReason.NoAccessibleRoute } ?: false

    /** Floor level of the visitor's last position. */
    var visitorLevel by mutableStateOf<Double?>(null)
        private set
    private var modeState by mutableStateOf(if (debug?.visitList == true) VisitMode.LIST else VisitMode.MAP)

    /** Map or List. A change reports [VisitEvent.ModeChanged]. */
    var mode: VisitMode
        get() = modeState
        set(value) {
            if (value == modeState) return
            modeState = value
            onEvent(VisitEvent.ModeChanged(value))
        }
    var places by mutableStateOf(emptyList<VenuePlace>())
        private set
    var galleries by mutableStateOf(emptyList<Gallery>())
        private set

    /** Name of the geofence (gallery) the visitor is in, from `geofenceEvents()`. */
    var currentGallery by mutableStateOf<String?>(null)
        private set

    /** The gallery the visitor is in, from the gallery polygons. */
    var visitorGallery by mutableStateOf<Gallery?>(null)
        private set

    /** The stop whose gallery the visitor has entered ([GalleryFirst]). */
    private var reachedStopId by mutableStateOf<String?>(null)

    /** Galleries the visitor has been in during this visit. */
    private var visitedGalleries = emptySet<String>()

    /** The stop of "Skip artwork?". */
    var stopToSkip by mutableStateOf<JourneyStop?>(null)
        private set
    var isConfirmingExit by mutableStateOf(false)
        private set
    /** Journey Complete, once every stop is done or skipped. */
    var isShowingWrapUp by mutableStateOf(false)
    var isShowingAmenities by mutableStateOf(false)

    /** The amenity picked from search, shown on a card before it becomes a detour. */
    var selectedAmenity by mutableStateOf<VenuePlace?>(null)

    /** The amenity whose detour waits for "Change your route?". */
    var amenityToConfirm by mutableStateOf<VenuePlace?>(null)

    /** EC04a–c: the full-screen "Where are you?" choice. */
    var locationChoice by mutableStateOf<LocationChoice?>(null)
        private set

    /** EC04d: the soft prompt for an uncertain position near the active stop. */
    var lowConfidenceStop by mutableStateOf<JourneyStop?>(null)
        private set

    /** The pairing flow, opened from the wristband-ended sheet. */
    var pairing by mutableStateOf<WristbandPairingFlow?>(null)
        private set

    /** `true` from "Connect a wristband" until the pairing flow closes. Hides the alert sheet. */
    var isPairing by mutableStateOf(false)
        private set

    /**
     * The time of the visitor's last answer per prompt kind. A kind is not shown again for
     * `LocationChoice.QUIET_PERIOD`.
     */
    private val answeredAmbiguity = mutableMapOf<String, Instant>()
    private var isClosed = false

    /** The visit card state (iOS `VisitCard.state`). */
    val cardState: VisitCardState
        get() = VisitCardState.make(progress.activeStop, progress.hasArrived, progress.isFinished, visitorLevel)

    /** The gallery of the active stop, from the gallery polygons. */
    private val stopGallery: Gallery?
        get() = progress.activeStop?.let { Gallery.containing(it.coordinate, it.level, galleries) }

    /** The gallery the card names instead of the artwork (DAILY-CALL R2). */
    val galleryAhead: Gallery?
        get() = GalleryFirst.galleryAhead(progress.activeStop?.id, stopGallery, reachedStopId)

    /** Display number of the active stop. */
    val activeStopNumber: Int?
        get() = progress.activeStop?.let { VisitPlan.numbers(progress.journey)[it.id] }

    init {
        session.journeyStopMarkerStyle = MapStyle.journeyStops
        controller.onArtworkDone = { stop, isActiveStop ->
            onEvent(if (isActiveStop) VisitEvent.ArtworkReached(stop.toCore()) else VisitEvent.ArtworkVisitedOutOfOrder(stop.toCore()))
        }
        scope.launch { controller.navigator.events.collect { onEvent(VisitEvent.Journey(it)) } }
        scope.launch {
            controller.navigator.noRouteReasons.collect {
                noRouteReasons = it
                updateNotification()
            }
        }
        monitor.start()
        scope.launch {
            loadVenue()
            refreshMapLayers()
            controller.start()
            // Starts the heading-up follow at `MapOptions.recentreZoom` once the journey is drawn,
            // the visitor has a position and the camera takes commands (PM review 2026-10-12: the
            // map did not turn until "Centre on me"; PM item 13273799998: the map stayed at world
            // scale). A recentre before the camera is ready is lost.
            combine(session.position, session.isCameraReady) { position, ready -> position != null && ready }
                .first { it }
            recentre(withHeading = true, reason = "journey started")
        }
        scope.launch {
            val navigator = controller.navigator
            combine(navigator.journey, navigator.guidance) { _, guidance -> guidance }.collect { guidance ->
                val wasFinished = progress.isFinished
                progress = readProgress()
                markGalleryReached()
                this@VisitViewModel.guidance = guidance
                controller.checkArtworkGeofences()
                if (progress.isFinished && !wasFinished) isShowingWrapUp = true
                controller.save()
                refreshMapLayers()
                refreshConnector()
                updateNotification()
                reportStopNearby(session.position.value, navigator.activeStop, guidance)
            }
        }
        scope.launch {
            session.position.collect { position ->
                if (position == null) return@collect
                reportStopNearby(position, controller.navigator.activeStop, controller.navigator.guidance.value)
                if (visitorLevel != position.floor?.level) {
                    visitorLevel = position.floor?.level
                    updateNotification()
                }
                visitorGallery = Gallery.containing(Coordinate(position.coordinate.latitude, position.coordinate.longitude), position.floor?.level, galleries)
                markGalleryReached()
            }
        }
        scope.launch {
            session.selectedFloor.collect {
                refreshMapLayers()
                refreshConnector()
            }
        }
        scope.launch {
            venue.sdk.geofenceEvents().collect { event ->
                FieldLog.log("geofence: $event")
                // Only gallery geofences set the current gallery; the artwork geofences inside them do not.
                when {
                    event is GeofenceEvent.Entered && event.geofence.isGallery -> {
                        val geofence = event.geofence
                        currentGallery = geofence.name
                        visitedGalleries = visitedGalleries + (galleries.firstOrNull { it.name == geofence.name }?.id ?: geofence.id)
                        refreshMapLayers()
                    }
                    event is GeofenceEvent.Exited && event.geofence.isGallery ->
                        if (currentGallery == event.geofence.name) currentGallery = null
                    event is GeofenceEvent.Entered && event.geofence.isArtwork ->
                        controller.artworkGeofenceEntered(event.geofence.remoteId)
                }
            }
        }
        if (FieldLog.isEnabled) logGuidance()
        // Ambiguity is checked every AMBIGUITY_INTERVAL_MILLIS (4 s), not on every position.
        scope.launch {
            while (true) {
                delay(AMBIGUITY_INTERVAL_MILLIS)
                evaluateAmbiguity()
            }
        }
    }

    private fun reportStopNearby(position: VenuePosition?, stop: MapJourneyStop?, guidance: RouteGuidance?) {
        stopNearby.update(position, stop, guidance)?.let { onEvent(VisitEvent.StopNearby(it.toCore())) }
    }

    /** The host app changed the accessibility needs: the leg on screen is routed again. */
    fun setAccessibility(accessibility: RouteAccessibility) {
        scope.launch { controller.setAccessibility(accessibility) }
    }

    /** The app language changed: map labels and place titles follow it. */
    fun setLanguage(appLanguage: String?) {
        val language = MapLanguage.forAppLanguage(appLanguage)
        if (language == this.language) return
        this.language = language
        session.options = session.options.copy(language = language)
        scope.launch {
            loadVenue()
            refreshMapLayers()
        }
    }

    private suspend fun loadVenue() {
        places = venue.places(language)
        controller.artworkRemoteIds = buildMap { places.forEach { place -> place.remoteId?.let { putIfAbsent(place.id, it) } } }
        controller.artworkAreas = venue.artworkAreas()
        // The journey map shows artworks only as the numbered stop markers. Amenities, galleries
        // and level changers stay (PM review 2026-10-12).
        session.hiddenFeatureIds = places.filter { it.isArtwork }.map { it.id }.toSet()
        galleries = venue.galleries(language)
        if (FieldLog.isEnabled) FieldLog.log("galleries: " + galleries.joinToString { "${it.name} L${it.level.toInt()}" })
    }

    /** `recentre` with a field-log line naming the caller, as iOS `VisitView.recentre(withHeading:reason:)`. */
    fun recentre(withHeading: Boolean? = null, reason: String) {
        FieldLog.log("recentre ($reason) · heading ${withHeading ?: "default"} · mode before ${session.cameraMode.value}")
        session.recentre(withHeading = withHeading)
    }

    /** Field log: the route guidance once per second and the navigator's events, as iOS `VisitView`. */
    private fun logGuidance() {
        // Camera diagnostics for the map library: why the heading-up follow does not start on a
        // device (PM review 2026-10-12).
        // Which style the map shows, and why it fell back (PM item 13273799998: a world map).
        scope.launch { session.styleSource.collect { FieldLog.log("map style: $it") } }
        scope.launch { session.lastError.collect { if (it != null) FieldLog.log("map error: $it") } }
        scope.launch {
            session.cameraMode.collect { mode ->
                FieldLog.log("camera mode $mode · ready ${session.isCameraReady.value}")
            }
        }
        scope.launch {
            while (true) {
                delay(1_000)
                val navigator = controller.navigator
                val position = session.position.value
                val guidance = navigator.guidance.value
                val stop = navigator.activeStop
                FieldLog.log(
                    String.format(
                        Locale.ROOT,
                        "guidance stop %s L%s · visitor L%s acc %.1f · remaining %.1f arrived %s dwell %.2f offRoute %s (%.1f m) · gallery %s",
                        stop?.title ?: "-", stop?.floor?.level?.roundToInt()?.toString() ?: "-",
                        position?.floor?.level?.roundToInt()?.toString() ?: "-", position?.horizontalAccuracy ?: -1.0,
                        guidance?.remainingMeters ?: -1.0, navigator.hasArrived, guidance?.arrivalProgress ?: 0.0,
                        guidance?.isOffRoute ?: false, guidance?.offRouteMeters ?: -1.0, currentGallery ?: "-",
                    ),
                )
            }
        }
        scope.launch {
            val navigator = controller.navigator
            navigator.events.collect { event ->
                val unreachable = navigator.overview.value.unreachableStopIds.size
                FieldLog.log("journey event: $event · active ${navigator.activeStop?.title ?: "none"} · unreachable $unreachable")
            }
        }
    }

    private fun readProgress(): Progress {
        val navigator = controller.navigator
        return Progress(
            journey = navigator.journey.value.toCore(),
            activeStop = navigator.activeStop?.toCore(),
            hasArrived = navigator.hasArrived,
            isFinished = navigator.isFinished,
            isDetour = navigator.detourStop != null,
        )
    }

    private fun markGalleryReached() {
        reachedStopId = GalleryFirst.reachedStopId(progress.activeStop?.id, stopGallery, visitorGallery, reachedStopId)
    }

    // Map layers

    /** Gallery fills for the current journey and floor. The map library draws the stop markers. */
    private fun refreshMapLayers() {
        if (isClosed) return
        val journey = progress.journey
        val active = progress.activeStop?.id
        layers.update(
            JourneyMapContent.fills(journey, active, galleries, visitedGalleries),
            session.selectedFloor.value.level,
        )
    }

    /**
     * The line from the visitor to the start of the route ahead, on the visitor's floor only. The
     * navigator updates the guidance on every fix, after the position.
     */
    private fun refreshConnector() {
        if (isClosed) return
        val guidance = guidance
        val start = guidance?.progress?.point
        val position = session.position.value
        val onFloor = position?.floor?.let { VenueLevel.same(it.level, session.selectedFloor.value.level) } ?: true
        // Within CONNECTOR_END_METERS of the route's end there is no line: it would lead straight
        // to the stop, through walls (PM review 2026-10-12). Off while SHOWS_CONNECTOR is false.
        if (!SHOWS_CONNECTOR || guidance == null || guidance.hasArrived || guidance.remainingMeters < CONNECTOR_END_METERS ||
            start == null || position == null || !onFloor
        ) {
            layers.updateConnector(null, null)
            return
        }
        layers.updateConnector(
            Coordinate(position.coordinate.latitude, position.coordinate.longitude),
            Coordinate(start.latitude, start.longitude),
        )
    }

    // Notification (iOS Live Activity)

    private fun updateNotification() {
        if (isClosed) return
        val instruction = if (hasNoAccessibleRoute) UiText.Resource("visit_no_accessible_route") else guidance?.let { GuidanceText.instruction(it.toCore()) }
        notification.update(VisitActivity.contentState(cardState, progress.journey, progress.activeStop?.id, instruction))
    }

    // Card and list actions

    /** "Resume your Journey" after a detour and "Show the route from here" after leaving the route. */
    fun resumeJourney() {
        onEvent(VisitEvent.JourneyResumed)
        scope.launch { controller.resumeJourney() }
    }

    /** "Centre on me". */
    fun centreOnMe() {
        onEvent(VisitEvent.Recentred)
        recentre(reason = "Centre on me")
    }

    fun dismissOffRoute() = controller.dismissOffRoute()

    /** A swipe on [stop] in the list, or its "Skip" accessibility action: "Skip artwork?". */
    fun requestSkip(stop: JourneyStop) {
        stopToSkip = stop
        onEvent(VisitEvent.SkipRequested(stop))
    }

    fun confirmSkip() {
        val stop = stopToSkip ?: return
        stopToSkip = null
        onEvent(VisitEvent.StopSkipped(stop))
        scope.launch { controller.skip(stop.id) }
    }

    /** "Keep it". */
    fun keepStop() {
        val stop = stopToSkip ?: return
        stopToSkip = null
        onEvent(VisitEvent.SkipCancelled(stop))
    }

    fun undoSkip() {
        val id = controller.lastSkippedStopId.value
        progress.journey.stops.firstOrNull { it.id == id }?.let { onEvent(VisitEvent.SkipUndone(it)) }
        scope.launch { controller.undoSkip() }
    }

    /**
     * Shows the nearest place of [kind] on a card. "Go here" and "Change your route?" add it as a
     * journey detour (PM review 2026-10-12).
     */
    fun detour(kind: AmenityKind) {
        isShowingAmenities = false
        val here = session.position.value
        val from = here?.let { Coordinate(it.coordinate.latitude, it.coordinate.longitude) }
        selectedAmenity = kind.nearest(places, from, here?.floor?.level)
        selectedAmenity?.let { onEvent(VisitEvent.AmenitySelected(it)) }
    }

    /** "Go here" on the amenity card: asks "Change your route?" first. */
    fun confirmAmenity() {
        amenityToConfirm = selectedAmenity
        selectedAmenity = null
    }

    /** "Go here" in "Change your route?": the amenity becomes a detour. */
    fun goToAmenity() {
        val place = amenityToConfirm ?: return
        amenityToConfirm = null
        onEvent(VisitEvent.DetourStarted(place))
        scope.launch { controller.detour(place) }
    }

    /** The gallery of the next artwork, or the artwork, for "Change your route?". */
    val nextStopName: String?
        get() {
            val stop = progress.journey.stops.firstOrNull {
                it.kind != StopKind.DETOUR && (it.state == StopState.ACTIVE || it.state == StopState.PENDING)
            } ?: return null
            return Gallery.containing(stop.coordinate, stop.level, galleries)?.name ?: stop.title
        }

    // Leaving

    /** Back: a visit in progress asks first (03.10); a visit not started or finished closes. */
    fun requestClose() {
        if (VisitPlan.asksBeforeLeaving(progress.journey, progress.isFinished)) {
            isConfirmingExit = true
            onEvent(VisitEvent.ExitRequested)
        } else {
            onClose()
        }
    }

    /** "Leave". */
    fun leave() {
        isConfirmingExit = false
        onEvent(VisitEvent.ExitConfirmed)
        onClose()
    }

    /** "Carry on". */
    fun carryOn() {
        isConfirmingExit = false
        onEvent(VisitEvent.ExitCancelled)
    }

    /** Journey Complete closed: the visit is over. */
    fun finishVisit() {
        isShowingWrapUp = false
        controller.end()
        onClose()
    }

    fun backToHub() = onClose()

    // Ambiguous location (EC04)

    private val isPresentingSomething: Boolean
        get() = locationChoice != null || lowConfidenceStop != null || stopToSkip != null || isConfirmingExit ||
            controller.isOffRoute.value || amenityToConfirm != null || isShowingWrapUp || isPairing ||
            wristband.bindingState.value is BindingState.Ended

    private fun evaluateAmbiguity() {
        if (isPresentingSomething || monitor.health.value != TrackingHealth.TRACKING) return
        val fix = monitor.lastFix.value ?: return
        val navigator = controller.navigator
        val input = LocationAmbiguity.Input(
            coordinate = fix.coordinate,
            level = fix.level,
            horizontalAccuracy = maxOf(0.0, fix.accuracy),
            activeStop = if (navigator.hasArrived) null else progress.activeStop,
            galleries = LocationAmbiguity.galleries(places),
            currentGallery = currentGallery,
        )
        val ambiguity = LocationAmbiguity.evaluate(input) ?: return
        if (LocationChoice.isQuiet(ambiguity.kind, answeredAmbiguity, Instant.now())) return
        FieldLog.log("ambiguous location: ${ambiguity.kind}")
        when (ambiguity) {
            is LocationAmbiguity.LowConfidence -> lowConfidenceStop = ambiguity.stop
            else -> locationChoice = LocationChoice.of(ambiguity)
        }
    }

    /** EC04c, after the sheet that asked has gone. */
    fun openManualChoice() {
        scope.launch {
            delay(SHEET_SWAP_DELAY_MILLIS)
            locationChoice = LocationChoice.manual(progress.journey)
        }
    }

    fun pick(option: LocationChoice.Option, choice: LocationChoice) {
        answeredAmbiguity[choice.kind] = Instant.now()
        locationChoice = null
        when (val action = option.action) {
            is LocationChoice.Action.ActivateStop -> {
                scope.launch { controller.activate(action.stopId) }
                mode = VisitMode.MAP
            }
            is LocationChoice.Action.InGallery -> currentGallery = action.name
            LocationChoice.Action.SomewhereElse -> Unit
        }
    }

    fun closeChoice(choice: LocationChoice) {
        answeredAmbiguity[choice.kind] = Instant.now()
        locationChoice = null
    }

    /** EC04d "That's right". */
    fun confirmLowConfidence() {
        answeredAmbiguity["low"] = Instant.now()
        lowConfidenceStop = null
    }

    /** EC04d "Not here": the manual choice. */
    fun rejectLowConfidence() {
        answeredAmbiguity["low"] = Instant.now()
        lowConfidenceStop = null
        openManualChoice()
    }

    // Tracking alerts

    /** Re-attaches the wristband binding's provider, which reopens its relay-api stream. */
    fun searchAgain() {
        scope.launch { venue.attachBinding() }
    }

    /** Adds the Welcome Desk as a detour, so the route leads there and then back to the journey. */
    fun routeToDesk() {
        val desk = VenuePlace.welcomeDesk(places) ?: return
        onEvent(VisitEvent.DetourStarted(desk))
        scope.launch { controller.detour(desk) }
        mode = VisitMode.MAP
    }

    /** Closes the wristband-ended sheet and opens the pairing flow once the sheet is gone. */
    fun connectWristband() {
        isPairing = true
        scope.launch {
            delay(SHEET_SWAP_DELAY_MILLIS)
            pairing = WristbandPairingFlow.of(wristband, scope)
        }
    }

    /** After a bind, re-attaching the provider reopens the relay-api position stream. */
    fun pairingClosed() {
        pairing = null
        isPairing = false
        if (wristband.isActive) scope.launch { venue.attachBinding() }
    }

    /** Ends the screen's work: the notification, the monitor, the navigator and the map session. The journey stays stored. */
    fun close() {
        if (isClosed) return
        isClosed = true
        notification.end()
        monitor.stop()
        controller.close()
        layers.detach()
        session.close()
        scope.cancel()
    }

    private companion object {
        const val AMBIGUITY_INTERVAL_MILLIS = 4_000L

        /** No connector this close to the route's end (metres). */
        const val CONNECTOR_END_METERS = 2.0

        /**
         * False: no connector. Its bend moved with every fix, so the route appeared to be redrawn on
         * every fix (PM review 2026-10-12, screen recording 21-26-05), as iOS `showsConnector`.
         */
        const val SHOWS_CONNECTOR = false

        /** A sheet opens this long after the one before it starts to close, as on iOS. */
        const val SHEET_SWAP_DELAY_MILLIS = 400L
    }
}
