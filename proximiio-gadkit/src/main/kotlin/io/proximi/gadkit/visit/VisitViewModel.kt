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
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.LocationAmbiguity
import io.proximi.gadkit.visit.StopKind
import io.proximi.gadkit.visit.StopState
import io.proximi.gadkit.visit.VisitActivity
import io.proximi.gadkit.visit.VisitCardState
import io.proximi.gadkit.visit.VisitPlan
import io.proximi.gadkit.DebugLaunch
import io.proximi.gadkit.map.FloorWithoutPositioningGuide
import io.proximi.gadkit.map.JourneyMapLayers
import io.proximi.gadkit.map.LevelChangeMarker
import io.proximi.gadkit.map.MapStyle
import io.proximi.gadkit.map.AccessibleRoutes
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.map.gadIcon
import io.proximi.gadkit.map.toCore
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.map.artworkAreas
import io.proximi.gadkit.map.GalleryDestination
import io.proximi.gadkit.map.PlaceFraming
import io.proximi.gadkit.map.venueGeofences
import io.proximi.sdk.VenueDataKind
import io.proximi.sdk.enteredGeofenceIds
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import io.proximi.gadkit.map.galleries
import io.proximi.gadkit.map.levelChangers
import io.proximi.gadkit.map.isArtwork
import io.proximi.gadkit.map.isGallery
import io.proximi.gadkit.map.places
import io.proximi.gadkit.wristband.TrackingMonitor
import io.proximi.gadkit.IncomingJourney
import io.proximi.gadkit.ProximiDestination
import io.proximi.gadkit.ProximiMapView
import io.proximi.gadkit.hasPrompt
import io.proximi.map.core.JourneyDeviationPolicy
import io.proximi.sdk.computeRoute
import io.proximi.sdk.wayfinding.WayfindingRoutingError
import io.proximi.sdk.core.model.ProximiioCoordinate
import io.proximi.gadkit.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.proximi.map.core.RouteGuidance
import io.proximi.sdk.wayfinding.NoRouteReason
import io.proximi.map.core.VenuePosition
import io.proximi.map.core.JourneyEvent
import io.proximi.map.core.JourneyStop as MapJourneyStop
import io.proximi.map.live.JourneyNavigator
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
import io.proximi.map.kit.MapCameraFollow
import kotlinx.coroutines.flow.collectLatest
import io.proximi.gadkit.GadSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.toJavaDuration

/**
 * State and actions of the journey screen, as the `@State` of iOS `VisitView`. Owned by
 * [VisitHolder] for as long as `VisitScreen` is on screen, so it survives an activity recreation
 * (a language change); [close] when the screen leaves. Main thread only.
 *
 * It owns the map session (the map view attaches to it), the [VisitController], the
 * [TrackingMonitor] (reported as [VisitEvent.TrackingChanged], no alert), the journey map layers
 * and the visit notification.
 *
 * [options] turn the bottom bar, the background, the prompts, the journey logic
 * ([VisitController] `managesProgress`) and the journey notification on. `ProximiMap` uses all of
 * them; `ProximiMapView` none by default. A journey without stops shows the map alone.
 *
 * @param onClose the host app's `onClose` of `VisitScreen` (iOS `onClose`): Back, "Leave the tour"
 *   and the close button of Journey Complete. `null`: no Back and no "Leave the tour here"; Journey
 *   Complete's close button returns to the map.
 * @param onEvent the host app's `onVisitEvent` of `VisitScreen` (iOS `onVisitEvent`).
 * @param persist stores the journey after each change (`VisitController.persist`).
 */
internal class VisitViewModel(
    private val application: Application,
    val venue: VenueService,
    journey: Journey,
    parentScope: CoroutineScope,
    private val debug: DebugLaunch.Options?,
    /** The accessibility needs when the screen opens; [setAccessibility] changes them. */
    accessibility: RouteAccessibility,
    /** Personalised or curated: the stop card, the list and skip follow it. */
    val kind: JourneyKind = JourneyKind.PERSONALISED,
    /** What the screen does beyond drawing the journey. */
    val options: Set<ProximiMapView.Option> = ProximiMapView.Option.ALL,
    /** Map or List when the screen opens. */
    initialMode: VisitMode = VisitMode.MAP,
    private val persist: (Journey?) -> Unit,
    private val onClose: (() -> Unit)?,
    /** The host app's `onVisitEvent` of `VisitScreen`. Called on the main thread. */
    private val onEvent: (VisitEvent) -> Unit = {},
    /** Gets the navigator before the journey starts (`ProximiMapView`'s `onNavigator`). */
    private val onNavigator: ((JourneyNavigator) -> Unit)? = null,
    /** The host app's destination when the screen opens (`ProximiMapView`'s `destination`). */
    destination: ProximiDestination? = null,
    /** Sets the host app's destination back to `null` when the route to it ends. */
    private val onDestinationChange: (ProximiDestination?) -> Unit = {},
    /** The host app's pause when the screen opens (`ProximiMapView`'s `isPaused`). */
    private val isPausedAtStart: Boolean = false,
    /** Sets the host app's pause back to `false` after "Resume your Journey" on the paused card. */
    private val onPausedChange: (Boolean) -> Unit = {},
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

    /** The host app's times and distances, read once when the screen opens. */
    val settings = venue.settings

    val session = ProximiioMapSession(venue.sdk, application, MapStyle.options(language, routeMarkers = false, settings = settings))
        .also { MapStyle.useAmenityIcons(it, application) }
    val controller = VisitController(
        session,
        journey,
        persist,
        scope,
        accessibility,
        amenityIconName = { place ->
            AmenityKind.entries.firstOrNull { it.matches(place) }?.let { application.resources.getResourceEntryName(it.gadIcon.glyph) }
        },
        autoAdvance = debug?.visitAutoAdvance == true,
        keepsOrder = kind.keepsOrder,
        countsOutOfOrder = kind.countsOutOfOrder,
        managesProgress = ProximiMapView.Option.PROGRESS in options,
        deviationPolicy = if (options.hasPrompt(ProximiMapView.Option.OFF_ROUTE_PROMPT)) JourneyDeviationPolicy.ASK_APP else JourneyDeviationPolicy.AUTOMATIC,
        settings = settings,
    )
    val monitor = TrackingMonitor(venue.sdk, scope, settings)
    val layers = JourneyMapLayers()

    /** The lift or stairs view and the map of a floor without positioning. */
    val floorGuide = FloorWithoutPositioningGuide(settings, scope)
    /** The journey notification (iOS Live Activity), with [ProximiMapView.Option.JOURNEY_NOTIFICATION] and stops only. */
    private val notification = if (ProximiMapView.Option.JOURNEY_NOTIFICATION in options && journey.stops.isNotEmpty()) {
        VisitNotification(application, settings.journeyNotificationMinimumInterval.inWholeMilliseconds / 1000.0)
    } else {
        null
    }

    /** The active stop less than [GadSettings.stopNearbyMeters] away, once per stop in this visit. */
    private val stopNearby = StopNearbyDetector(settings.stopNearbyMeters)

    /** Whether the journey has a stop. Without one the screen shows the map alone. */
    val hasStops: Boolean = journey.stops.isNotEmpty()

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
    private var modeState by mutableStateOf(if (debug?.visitList == true) VisitMode.LIST else initialMode)

    /** Map or List. A change reports [VisitEvent.ModeChanged]. */
    var mode: VisitMode
        get() = modeState
        set(value) {
            if (value == modeState) return
            modeState = value
            onEvent(VisitEvent.ModeChanged(value))
        }

    /** List mode with a journey. Without stops the map shows, whatever [mode] is. */
    val showsList: Boolean get() = mode == VisitMode.LIST && hasStops
    var places by mutableStateOf(emptyList<VenuePlace>())
        private set
    var galleries by mutableStateOf(emptyList<Gallery>())
        private set

    /** Gallery names by gallery id, for the list's headers ([Gallery.names]). */
    var galleryNames by mutableStateOf(emptyMap<String, String>())
        private set

    /** Name of the geofence (gallery) the visitor is in, from `geofenceEvents()`. */
    var currentGallery by mutableStateOf<String?>(null)
        private set

    /** The gallery the visitor is in, from the gallery polygons. */
    var visitorGallery by mutableStateOf<Gallery?>(null)
        private set

    /** "Rerouting you…": the journey has been routed again for longer than 0.5 s. */
    var showsRerouting by mutableStateOf(false)
        private set

    /**
     * The gallery the leg to the active artwork ends at ([VisitController.galleryAhead]), or `null`
     * when it ends at the artwork.
     */
    var galleryAheadDestination by mutableStateOf<GalleryDestination?>(null)
        private set

    /** The artworks and level changers, which the map draws itself. The gallery a leg leads to first is hidden too. */
    private var hiddenFeatureIds = emptySet<String>()

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

    /** The host app's latest destination. */
    private var requestedDestination: ProximiDestination? = destination

    /** A destination the screen routes to: the id the host app gave and the place's feature id. */
    private data class RoutedDestination(val requested: String, val placeId: String)

    /** The destination the screen routes to. */
    private var routedDestination: RoutedDestination? = null

    /** The places are loaded and the journey started: a destination can be routed to. */
    private var isReady = false

    /** The amenity routed to from search on a map without a journey (no navigator). */
    var routedAmenity by mutableStateOf<VenuePlace?>(null)
        private set

    /** Why there is no route to [routedAmenity] (a string resource), or `null`. */
    var amenityRouteError by mutableStateOf<Int?>(null)
        private set

    /** EC04d: the soft prompt for an uncertain position near the active stop. */
    var lowConfidenceStop by mutableStateOf<JourneyStop?>(null)
        private set

    /**
     * The time of the visitor's last answer per prompt kind. A kind is not shown again for
     * [GadSettings.locationQuestionQuietPeriod].
     */
    private val answeredAmbiguity = mutableMapOf<String, Instant>()
    private var isClosed = false

    /** The visit card state (iOS `VisitCard.state`). */
    val cardState: VisitCardState
        get() = VisitCardState.make(progress.activeStop, progress.hasArrived, progress.isFinished, visitorLevel)

    /**
     * The gallery the card names instead of the artwork (DAILY-CALL R2; PM, Monday "Navigation to a
     * gallery"): while the leg ends at the gallery of the active artwork. A curated tour has none.
     */
    val galleryAhead: Gallery?
        get() = galleryAheadDestination?.gallery

    /** The place of the active stop while the card's title names it: the card shows its AR and audio badges. */
    val namedArtwork: VenuePlace?
        get() {
            val stop = progress.activeStop ?: return null
            val namesStop = when (cardState) {
                is VisitCardState.Walking -> floorGuide.isActive || galleryAhead == null
                is VisitCardState.AtStop -> true
                else -> floorGuide.isActive
            }
            return if (namesStop) places.firstOrNull { it.id == (stop.poiId ?: stop.id) } else null
        }

    /** The visitor's gallery for the card's caption ("GALLERY · …"), in either kind of journey (iOS 1.5.0). */
    val cardVisitorGallery: Gallery?
        get() = visitorGallery

    /** Display number of the active stop. */
    val activeStopNumber: Int?
        get() = progress.activeStop?.let { VisitPlan.numbers(progress.journey)[it.id] }

    init {
        // Before `controller.start` below: the host app's collectors get the journey's first events.
        onNavigator?.invoke(controller.navigator)
        session.journeyStopMarkerStyle = MapStyle.journeyStops
        controller.onArtworkDone = { stop, isActiveStop ->
            onEvent(if (isActiveStop) VisitEvent.ArtworkReached(stop.toCore()) else VisitEvent.ArtworkVisitedOutOfOrder(stop.toCore()))
        }
        floorGuide.onEnter = { change ->
            // Under the Map / List toggle, beside the map buttons, above the stop card.
            val density = application.resources.displayMetrics.density
            FloorWithoutPositioningGuide.show(change, session, FloorWithoutPositioningGuide.insets(density, top = 100, left = 40, bottom = 220, right = 90))
            onEvent(VisitEvent.FloorWithoutPositioningEntered(change.level))
        }
        floorGuide.onLeave = { change ->
            session.followMyFloor()
            recentre(withHeading = true, reason = "floor without positioning left")
            onEvent(VisitEvent.FloorWithoutPositioningLeft(change.level))
        }
        scope.launch {
            controller.navigator.events.collect { event ->
                // The navigator walks to an artwork visited out of order for a moment.
                if (event is JourneyEvent.LegChanged && event.to.id == controller.passingStopId.value) return@collect
                if (controller.isPaused.value && isRouteDeviation(event)) return@collect
                onEvent(VisitEvent.Journey(event))
                if (event is JourneyEvent.DetourEnded && routedDestination?.placeId == (event.stop.poiId ?: event.stop.id)) clearDestination()
            }
        }
        scope.launch {
            controller.navigator.noRouteReasons.collect {
                noRouteReasons = it
                updateNotification()
            }
        }
        monitor.start()
        // No tracking or wristband alerts: the host app handles the wristband states (PM 2026-10-15).
        scope.launch { monitor.health.drop(1).collect { onEvent(VisitEvent.TrackingChanged(it)) } }
        // No dot while a privacy zone holds it (`MapStyle.hidingPosition`).
        scope.launch { monitor.health.collect(::applyPositionStyle) }
        scope.launch { snapshotFlow { visitorGallery?.id }.distinctUntilChanged().collect { applyFollowZoom() } }
        scope.launch {
            // "Rerouting you…" after 0.5 s of routing; hidden when the routing ends (PM, Monday
            // "Rerouting calculation view", 2026-10-26).
            controller.isRerouting.collectLatest { isRerouting ->
                if (!isRerouting) {
                    showsRerouting = false
                    return@collectLatest
                }
                delay(REROUTING_DELAY_MILLIS)
                showsRerouting = true
            }
        }
        scope.launch {
            loadVenue()
            refreshMapLayers()
            // A journey without stops shows the map alone; the navigator would report it finished.
            if (hasStops) {
                if (isPausedAtStart) controller.pause()
                controller.start()
                // A visitor who opens the journey inside the first artwork's geofence.
                checkArrival()
            }
            isReady = true
            destinationChanged(requestedDestination)
            if (!hasStops) return@launch
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
                val activeBefore = progress.activeStop?.id
                progress = readProgress()
                this@VisitViewModel.guidance = guidance
                val position = session.position.value
                floorGuide.update(
                    position?.let { Coordinate(it.coordinate.latitude, it.coordinate.longitude) },
                    position?.floor?.level,
                    levelChangeMarkers,
                    destinationId = navigator.detourStop?.id ?: navigator.activeStop?.id,
                )
                if (progress.activeStop?.id != activeBefore) checkArrival()
                if (progress.isFinished && !wasFinished && ProximiMapView.Option.JOURNEY_COMPLETE in options) isShowingWrapUp = true
                controller.save()
                refreshMapLayers()
                refreshConnector()
                refreshLevelChanger()
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
                // The map publishes guidance only on a leg with a route, so the arrival is checked on
                // the position (field test 2026-10-19).
                checkArrival()
                controller.checkCloserArtwork()
                controller.checkWrongFloor(floorGuide.isActive)
            }
        }
        scope.launch {
            controller.galleryAhead.collect { gallery ->
                galleryAheadDestination = gallery
                galleryAheadChanged(gallery)
            }
        }
        scope.launch {
            // The amenity picked in the menu while its card or route is on screen.
            snapshotFlow { selectedAmenity ?: routedAmenity }.distinctUntilChanged().drop(1).collect(::showAmenity)
        }
        scope.launch {
            // The venue data changed on the backend: places, galleries and level changers again; the
            // open journey takes changed POIs, a stop whose POI was deleted is skipped, and changed
            // features route the legs again.
            venue.data.version.drop(1).collect {
                val routeNetworkChanged = VenueDataKind.features in venue.data.changedKinds
                loadVenue()
                refreshMapLayers()
                for (stop in controller.venueChanged(places, galleries, routeNetworkChanged)) onEvent(VisitEvent.StopPlaceDeleted(stop))
            }
        }
        scope.launch {
            session.selectedFloor.collect {
                refreshMapLayers()
                refreshConnector()
                refreshLevelChanger()
            }
        }
        scope.launch {
            val events = venue.sdk.geofenceEvents()
            // The stream does not repeat the geofences entered before it started.
            controller.enteredGeofenceIds = venue.sdk.enteredGeofenceIds()
            checkArrival()
            events.collect { event ->
                when (event) {
                    is GeofenceEvent.Entered -> controller.enteredGeofenceIds += event.geofence.id
                    is GeofenceEvent.Exited -> controller.enteredGeofenceIds -= event.geofence.id
                    else -> Unit
                }
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

    /**
     * Arrival at an artwork, out of order and gallery first. On a floor without positioning the fixes
     * come from the floor the visitor left, so nothing is checked.
     */
    private fun checkArrival() {
        if (floorGuide.isActive) return
        controller.checkArtworkGeofences()
        controller.checkGalleryFirst()
    }

    private fun reportStopNearby(position: VenuePosition?, stop: MapJourneyStop?, guidance: RouteGuidance?) {
        stopNearby.update(position, stop, guidance)?.let { onEvent(VisitEvent.StopNearby(it.toCore())) }
    }

    /** The host app's journey after a change, applied to the open screen ([VisitController.apply]). */
    fun apply(incoming: IncomingJourney) {
        scope.launch { controller.apply(incoming.journey) }
    }

    /** The accessible routes button. `VisitScreen` routes the leg again with the new needs. */
    fun setAccessibleRoutes(isOn: Boolean) {
        AccessibleRoutes.set(application, isOn)
        onEvent(VisitEvent.AccessibleRoutesChanged(isOn))
    }

    /** The host app changed the accessibility needs: the leg on screen is routed again. */
    fun setAccessibility(accessibility: RouteAccessibility) {
        this.accessibility = accessibility
        scope.launch { controller.setAccessibility(accessibility) }
    }

    private var accessibility = accessibility

    /** The app language changed: map labels and place titles follow it. */
    /** No dot while a privacy zone holds it (`MapStyle.hidingPosition`). */
    private fun applyPositionStyle(health: TrackingHealth) {
        val position = MapStyle.options(language, routeMarkers = false, settings = settings).position
        val style = if (health == TrackingHealth.PRIVACY_ZONE) MapStyle.hidingPosition(position) else position
        if (session.options.position != style) session.options = session.options.copy(position = style)
    }

    /**
     * The zoom of "Centre on me" and of the follow: [GadSettings.galleryFollowZoom] inside a gallery
     * in a personalised journey, else [GadSettings.followZoom] (PM, Monday "Navigation to a gallery"
     * and "Curated tours: zoom level", 2026-10-27). A camera that follows the visitor zooms to it; one
     * the visitor moved stays.
     */
    private fun applyFollowZoom() {
        val zoom = followZoom(inGallery = visitorGallery != null, kind = kind, settings = settings)
        if (session.options.recentreZoom == zoom) return
        session.options = session.options.copy(recentreZoom = zoom)
        if (session.cameraMode.value != MapCameraFollow.Mode.FREE && !floorGuide.isActive) {
            FieldLog.log("follow zoom $zoom · in a gallery ${visitorGallery?.name ?: "-"}")
            session.recentre(zoom = zoom)
        }
    }

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
        // and level changers stay (PM review 2026-10-12). [layers] draws the level changers on
        // every floor they serve.
        val levelChangers = venue.levelChangers()
        layers.updateLevelChangers(levelChangers)
        hiddenFeatureIds = (places.filter { it.isArtwork }.map { it.id } + levelChangers.map { it.id }).toSet()
        session.hiddenFeatureIds = hiddenFeatureIds + listOfNotNull(galleryAheadDestination?.placeId)
        val destinations = Gallery.destinations(venue.venueGeofences(), places)
        controller.setGalleryFirst(kind.guidesToGalleryFirst, places, destinations)
        galleries = venue.galleries(language)
        galleryNames = Gallery.names(places)
        if (FieldLog.isEnabled) {
            FieldLog.log(
                "galleries: " + galleries.joinToString { "${it.name} L${it.level.toInt()}" } +
                    " · ${galleryNames.size} named by id · ${destinations.size} for gallery first",
            )
        }
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

    // Map layers

    /** Highlights [place] and shows it with the visitor ([PlaceFraming]); `null` removes the highlight. */
    private fun showAmenity(place: VenuePlace?) {
        layers.updateHighlight(JourneyMapLayers.highlight(place))
        place ?: return
        // Under the Map / List toggle, beside the map buttons, above the place card.
        val density = application.resources.displayMetrics.density
        PlaceFraming.show(place, session, FloorWithoutPositioningGuide.insets(density, top = 100, left = 40, bottom = 220, right = 90), settings.amenityFramingMinimumMeters)
    }

    /** The leg now ends at [gallery], or at the artwork for `null` (gallery first). */
    private fun galleryAheadChanged(gallery: GalleryDestination?) {
        refreshMapLayers()
        // The red gallery marker takes the place of the gallery's own POI.
        session.hiddenFeatureIds = hiddenFeatureIds + listOfNotNull(gallery?.placeId)
        FieldLog.log("gallery first: ${gallery?.let { "route ends at ${it.gallery.name}" } ?: "route ends at the artwork"}")
    }

    /** Gallery fills and the gallery marker for the current journey and floor. The map library draws the stop markers. */
    private fun refreshMapLayers() {
        if (isClosed) return
        val journey = progress.journey
        val active = progress.activeStop?.id
        val galleryAhead = galleryAheadDestination
        layers.update(
            JourneyMapContent.fills(journey, active, galleries, visitedGalleries, galleryAhead?.gallery),
            session.selectedFloor.value.level,
        )
        layers.updateGalleryMarker(galleryAhead?.let { JourneyMapLayers.GalleryMarker(it.entry, it.gallery.level, it.label) })
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

    /** Marks the lift or stairs of the route's next level change on the shown floor in red. */
    private fun refreshLevelChanger() {
        if (isClosed) return
        val level = session.selectedFloor.value.level
        val next = session.routeRenderer?.geometry?.markers?.firstOrNull { !it.isCompleted && VenueLevel.same(it.from.level, level) }
        layers.updateLevelChanger(
            next?.let { JourneyMapLayers.LevelChanger(Coordinate(it.coordinate.latitude, it.coordinate.longitude), level, it.featureType) },
        )
    }

    // Notification (iOS Live Activity)

    /** The route's level changes, from the drawn route. */
    val levelChangeMarkers: List<LevelChangeMarker>
        get() = session.routeRenderer?.geometry?.markers.orEmpty().map { it.toCore() }

    private fun updateNotification() {
        if (isClosed) return
        val instruction = if (hasNoAccessibleRoute) {
            UiText.Resource("visit_no_accessible_route")
        } else {
            guidance?.let { GuidanceText.instruction(it.toCore(), levelChangeMarkers) }
        }
        notification?.update(VisitActivity.contentState(cardState, progress.journey, progress.activeStop?.id, instruction))
    }

    // Card and list actions

    /** "Resume your Journey" after a detour and "Show the route from here" after leaving the route. */
    fun resumeJourney() {
        onEvent(VisitEvent.JourneyResumed)
        scope.launch { controller.resumeJourney() }
    }

    /** "Centre on me": the map follows the visitor's floor again, also after a floor without positioning. */
    fun centreOnMe() {
        floorGuide.leave()
        session.followMyFloor()
        onEvent(VisitEvent.Recentred)
        recentre(reason = "Centre on me")
    }

    /** The floor button: the map follows the visitor's floor again, also after a floor without positioning. */
    fun followMyFloor() {
        floorGuide.leave()
        session.followMyFloor()
    }

    fun dismissOffRoute() = controller.dismissOffRoute()

    /**
     * A swipe on [stop] in the list, or its "Skip" accessibility action: "Skip artwork?" with
     * [ProximiMapView.Option.SKIP_PROMPT], else only the event.
     */
    fun requestSkip(stop: JourneyStop) {
        if (options.hasPrompt(ProximiMapView.Option.SKIP_PROMPT)) stopToSkip = stop
        onEvent(VisitEvent.SkipRequested(stop))
    }

    fun confirmSkip() {
        val stop = stopToSkip ?: return
        stopToSkip = null
        // Without PROGRESS the host app skips the stop.
        if (ProximiMapView.Option.PROGRESS in options) scope.launch { controller.skip(stop.id) }
        onEvent(VisitEvent.StopSkipped(stop))
    }

    /** "Keep it". */
    fun keepStop() {
        val stop = stopToSkip ?: return
        stopToSkip = null
        onEvent(VisitEvent.SkipCancelled(stop))
    }

    /**
     * Shows the nearest place of [kind] on a card. "Add a stop" adds it as a journey detour (PM review
     * 2026-10-12).
     */
    fun detour(kind: AmenityKind) {
        isShowingAmenities = false
        val here = session.position.value
        val from = here?.let { Coordinate(it.coordinate.latitude, it.coordinate.longitude) }
        selectedAmenity = kind.nearest(places, from, here?.floor?.level)
        selectedAmenity?.let { onEvent(VisitEvent.AmenitySelected(it)) }
    }

    /**
     * "Add a stop" on the amenity card during a journey: the detour starts at once (PM, Monday "Add
     * stop confirmation", 2026-10-26: no second confirmation), and the map goes back to the visitor
     * (Monday "Adding amenity -> focus back on my location", 2026-10-27). "Go here" on a map without a
     * journey draws the route to the amenity.
     */
    fun confirmAmenity() {
        val place = selectedAmenity ?: return
        selectedAmenity = null
        if (!hasStops) {
            scope.launch { route(place) }
            return
        }
        scope.launch { controller.detour(place) }
        onEvent(VisitEvent.DetourStarted(place))
        onEvent(VisitEvent.JourneyPaused)
        session.followMyFloor()
        recentre(reason = "stop added")
    }

    /**
     * "Go here" on a map without a journey: the route from the visitor to [place], as on the museum
     * map. There is no navigator to add a detour to.
     */
    private suspend fun route(place: VenuePlace) {
        routedDestination?.let { if (it.placeId != place.id) clearDestination() }
        routedAmenity = place
        val here = session.position.value
        if (here == null) {
            amenityRouteError = R.string.waiting_for_your_position
            session.clearRoute()
            return
        }
        try {
            val route = withContext(Dispatchers.Default) {
                venue.sdk.computeRoute(
                    from = ProximiioCoordinate(here.coordinate.latitude, here.coordinate.longitude),
                    fromLevel = here.floor?.level ?: 0.0,
                    to = ProximiioCoordinate(place.coordinate.latitude, place.coordinate.longitude),
                    toLevel = place.level,
                    options = accessibility.routeOptions,
                )
            }
            if (routedAmenity?.id != place.id) return
            session.setRoute(route)
            amenityRouteError = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FieldLog.log("route to ${place.title} failed: $error")
            session.clearRoute()
            amenityRouteError = if ((error as? WayfindingRoutingError)?.reason is NoRouteReason.NoAccessibleRoute) {
                R.string.no_route_from_here_with_your_accessibility
            } else {
                R.string.no_route_from_here
            }
        }
    }

    /** "Close" on the card of [routedAmenity]. */
    fun endAmenityRoute() {
        routedAmenity = null
        amenityRouteError = null
        session.clearRoute()
        if (routedDestination != null) clearDestination()
    }

    /** "Your next stop is Gallery 103.": the gallery the leg ends at, else the next stop. */
    val nextStopName: String?
        get() = galleryAheadDestination?.gallery?.name ?: controller.navigator.activeStop?.title

    // Destination

    /**
     * The host app set `destination`, or set it back to `null`. A new destination replaces the route
     * of the previous one; `null` ends it.
     */
    fun destinationChanged(destination: ProximiDestination?) {
        requestedDestination = destination
        if (!isReady || destination?.poiId == routedDestination?.requested) return
        if (routedDestination != null && destination == null) {
            routedDestination = null
            scope.launch { endDestinationRoute() }
            return
        }
        destination ?: return
        scope.launch { route(destination) }
    }

    /**
     * Routes to the host app's destination at once, as "Add a stop" does: the visitor agreed with the
     * host app (the audio guide). A stop of the journey still to visit becomes the next stop instead.
     */
    private suspend fun route(destination: ProximiDestination) {
        val place = VenuePlace.place(destination.poiId, places) ?: return destinationFailed(destination.poiId, ProximiDestination.Failure.UNKNOWN_PLACE)
        // The feature id; the host app can give `properties.id`.
        val id = place.id
        val routed = RoutedDestination(destination.poiId, id)
        FieldLog.log("destination: ${place.title}")
        selectedAmenity = null
        isShowingAmenities = false
        routedDestination = routed
        if (!hasStops) {
            route(place)
            if (routedDestination != routed || routedAmenity?.id != id) return
            if (session.position.value == null) {
                endAmenityRoute()
                destinationFailed(routed.requested, ProximiDestination.Failure.NO_POSITION)
            } else if (amenityRouteError != null) {
                endAmenityRoute()
                destinationFailed(routed.requested, ProximiDestination.Failure.NO_ROUTE)
            }
            return
        }
        val stop = controller.navigator.journey.value.stops.firstOrNull { (it.poiId ?: it.id) == id && it.kind != MapJourneyStop.Kind.DETOUR }
        if (stop != null && stop.state in OPEN_STATES) {
            if (stop.state == MapJourneyStop.State.PENDING) controller.activate(stop.id)
            clearDestination()
            return
        }
        val stopId = controller.detour(place)
        onEvent(VisitEvent.DetourStarted(place))
        if (routedDestination != routed) return
        if (controller.navigator.noRouteReasons.value[stopId] != null) {
            controller.cancelDetour()
            destinationFailed(routed.requested, ProximiDestination.Failure.NO_ROUTE)
        }
    }

    /** Ends the route the destination started, after the host app set it to `null`. */
    private suspend fun endDestinationRoute() {
        if (hasStops) {
            if (controller.navigator.detourStop != null) controller.cancelDetour()
        } else {
            endAmenityRoute()
        }
    }

    private fun destinationFailed(id: String, reason: ProximiDestination.Failure) {
        FieldLog.log("destination $id unavailable: $reason")
        clearDestination()
        onEvent(VisitEvent.DestinationUnavailable(id, reason))
    }

    /** The route to the destination ended: the host app's destination goes back to `null`. */
    private fun clearDestination() {
        routedDestination = null
        if (requestedDestination != null) {
            requestedDestination = null
            onDestinationChange(null)
        }
    }

    // Pause

    /** The host app paused (`true`) or resumed (`false`) the journey. Reports no event. */
    fun pausedChanged(isPaused: Boolean) {
        if (!hasStops || !isReady) return
        if (isPaused) controller.pause() else scope.launch { controller.resumeFromPause() }
    }

    /** "Resume your Journey" on the card of a paused journey. */
    fun resumeFromPause() {
        onPausedChange(false)
        scope.launch { controller.resumeFromPause() }
        onEvent(VisitEvent.JourneyResumed)
    }

    // Leaving

    /** Back: a visit in progress asks first (03.10) with [ProximiMapView.Option.LEAVE_TOUR_PROMPT]; otherwise Back closes. */
    fun requestClose() {
        if (options.hasPrompt(ProximiMapView.Option.LEAVE_TOUR_PROMPT) && VisitPlan.asksBeforeLeaving(progress.journey, progress.isFinished)) {
            isConfirmingExit = true
            onEvent(VisitEvent.ExitRequested)
        } else {
            onClose?.invoke()
        }
    }

    /** "Leave", once the exit sheet has gone. */
    fun leave() {
        isConfirmingExit = false
        onEvent(VisitEvent.ExitConfirmed)
        onClose?.invoke()
    }

    /** "Carry on". */
    fun carryOn() {
        isConfirmingExit = false
        onEvent(VisitEvent.ExitCancelled)
    }

    /** Journey Complete closed: the visit is over. Without `onClose` the map shows again. */
    fun finishVisit() {
        isShowingWrapUp = false
        controller.end()
        onClose?.invoke()
    }

    // Ambiguous location (EC04)

    private val isPresentingSomething: Boolean
        // Without prompts the off-route state shows nothing and does not hold the report back.
        get() = lowConfidenceStop != null || stopToSkip != null || isConfirmingExit ||
            (options.hasPrompt(ProximiMapView.Option.OFF_ROUTE_PROMPT) && controller.isOffRoute.value) ||
            isShowingWrapUp || floorGuide.isActive || controller.isPaused.value

    private fun evaluateAmbiguity() {
        if (isPresentingSomething || monitor.health.value != TrackingHealth.TRACKING) return
        val fix = monitor.lastFix.value ?: return
        val navigator = controller.navigator
        val input = LocationAmbiguity.Input(
            coordinate = fix.coordinate,
            level = fix.level,
            horizontalAccuracy = maxOf(0.0, fix.accuracy),
            activeStop = if (navigator.hasArrived) null else progress.activeStop,
        )
        val ambiguity = LocationAmbiguity.evaluate(input, settings.uncertainAccuracyMeters) ?: return
        if (LocationAmbiguity.isQuiet(ambiguity.kind, answeredAmbiguity, Instant.now(), settings.locationQuestionQuietPeriod.toJavaDuration())) return
        FieldLog.log("ambiguous location: ${ambiguity.kind}")
        when (ambiguity) {
            is LocationAmbiguity.LowConfidence -> if (options.hasPrompt(ProximiMapView.Option.UNCERTAIN_POSITION_PROMPT)) {
                lowConfidenceStop = ambiguity.stop
            } else {
                // Reported once per quiet period, as an answered prompt.
                answeredAmbiguity[ambiguity.kind] = Instant.now()
                onEvent(VisitEvent.PositionUncertain(ambiguity.stop))
            }
        }
    }

    /** EC04d "That's right". */
    fun confirmLowConfidence() {
        answeredAmbiguity["low"] = Instant.now()
        lowConfidenceStop = null
    }

    /** EC04d "Not here": the answer is recorded and the prompt closes. */
    fun rejectLowConfidence() {
        answeredAmbiguity["low"] = Instant.now()
        lowConfidenceStop = null
    }

    /** Ends the screen's work: the notification, the monitor, the navigator and the map session. The journey stays stored. */
    fun close() {
        if (isClosed) return
        isClosed = true
        notification?.end()
        monitor.stop()
        controller.close()
        layers.detach()
        session.close()
        scope.cancel()
    }

    companion object {
        private const val AMBIGUITY_INTERVAL_MILLIS = 4_000L

        /** "Rerouting you…" shows after this much routing. */
        private const val REROUTING_DELAY_MILLIS = 500L

        /** The follow zoom: [GadSettings.galleryFollowZoom] in a gallery of a personalised journey. Pure; covered by tests. */
        internal fun followZoom(inGallery: Boolean, kind: JourneyKind, settings: GadSettings): Double {
            val zoom = settings.galleryFollowZoom
            return if (inGallery && kind.guidesToGalleryFirst && zoom != null) zoom else settings.followZoom
        }

        /** The states of a stop still to visit: a destination at such a stop makes it the next stop. */
        private val OPEN_STATES = setOf(MapJourneyStop.State.PENDING, MapJourneyStop.State.ACTIVE, MapJourneyStop.State.REACHED)

        /** The navigator's off-route events, not reported while the journey is paused. Pure; covered by tests. */
        internal fun isRouteDeviation(event: JourneyEvent): Boolean =
            event is JourneyEvent.LeftRoute || event is JourneyEvent.FarFromRoute ||
                event is JourneyEvent.OffRouteTooLong || event == JourneyEvent.ReturnedToRoute

        /** No connector this close to the route's end (metres). */
        private const val CONNECTOR_END_METERS = 2.0

        /**
         * False: no connector. Its bend moved with every fix, so the route appeared to be redrawn on
         * every fix (PM review 2026-10-12, screen recording 21-26-05), as iOS `showsConnector`.
         */
        private const val SHOWS_CONNECTOR = false
    }
}
