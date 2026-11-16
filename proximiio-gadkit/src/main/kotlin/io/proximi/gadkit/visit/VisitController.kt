package io.proximi.gadkit.visit

import io.proximi.gadkit.ArtworkVisitCause
import io.proximi.gadkit.GadSettings
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.map.VenueGeofence
import io.proximi.gadkit.map.VenueLevel
import io.proximi.gadkit.FieldLog
import java.util.Locale
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.ArtworkArea
import io.proximi.gadkit.visit.ArtworkArrival
import io.proximi.gadkit.visit.GeofenceDwell
import io.proximi.gadkit.visit.JourneyConstants

import io.proximi.gadkit.visit.VisitPlan
import io.proximi.map.core.JourneyDeviationPolicy
import io.proximi.map.core.JourneyEvent
import io.proximi.map.core.JourneyOrderOrigin
import io.proximi.map.core.JourneyOrderProposal
import io.proximi.map.core.JourneyOrderStrategy
import io.proximi.map.core.JourneyRules
import io.proximi.map.core.JourneyStop
import io.proximi.map.core.RouteFollowRules
import io.proximi.map.core.RouteGuidance
import io.proximi.map.core.RouteSnapRules
import io.proximi.map.core.RouteGeometry
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GalleryDestination
import io.proximi.map.core.MapCoordinate
import io.proximi.map.live.JourneyNavigator
import io.proximi.map.live.ProximiioJourneyRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.proximi.map.live.ProximiioMapSession
import io.proximi.sdk.wayfinding.RouteOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.time.Duration
import io.proximi.gadkit.visit.Journey as CoreJourney

/**
 * Owns the `JourneyNavigator` of a visit, persists the journey, and implements skip, as iOS
 * `VisitController`.
 *
 * Arrival and progress:
 * - Arrival at an artwork is the enter event of the active artwork's geofence
 *   ([artworkGeofenceEntered], [ArtworkArrival]). Nagarro's audio guide uses the same enter event.
 * - A visitor already inside the geofence when the leg starts gets no enter event. Every fix is
 *   therefore also checked against the artwork geofences ([checkArtworkGeofences]). A geofence the
 *   SDK counts the visitor inside ([enteredGeofenceIds]) counts too: the SDK exits a geofence only
 *   beyond 1.2 × its radius, and its event stream does not repeat the geofences entered before the
 *   screen opened (field test 2026-10-19). Since SDK 6.0.0-beta.29 the SDK also exits a geofence
 *   only `ProximiioConfiguration.geofenceExitDelay` after the position left it (`ProximiMap`:
 *   [GadSettings.geofenceExitDelay], 5 s; the SDK default is 3 s), so an artwork counts as inside
 *   up to that long after the visitor left it.
 * - The journey moves to the next stop when the visitor leaves the artwork
 *   ([GadSettings.advancesOnArtworkExit]; Nagarro, 2026-10-30): more than
 *   [GadSettings.artworkVisitedMeters] from the border of its geofence for
 *   [GadSettings.artworkVisitedSeconds], or at once when the visitor is in another open artwork's
 *   geofence (GAD PM, 2026-11-15; in a curated tour the next artwork's). The SDK's exit event does not
 *   move the journey (iOS 1.41.0). Until then the artwork is reached
 *   (`JourneyNavigator.markReached`): it stays the active stop with the destination marker, and the
 *   navigator draws the leg to the next stop from the visitor (`drawsNextLegWhileReached`; GAD PM and
 *   Nagarro, 2026-10-30). A reached artwork that the navigator moves past (a detour, a chosen stop) is
 *   reported visited too. Without the setting it moves on [GadSettings.artworkAdvanceDelay] (2 s) after
 *   the arrival.
 * - Artwork legs have no arrival by distance ([artworkGuidanceRules]). An artwork the visitor walks
 *   past without entering its geofence stays unvisited until it is skipped.
 * - Out of order: a visitor inside the geofence of another open artwork for
 *   [GadSettings.outOfOrderDwell] (0 while testing) makes it the current stop: it is reached, and
 *   visited by the rules of any reached artwork. The stops still ahead are ordered again from the
 *   visitor when it is visited (GAD PM, 2026-11-16; iOS 1.48.0). With
 *   [GadSettings.visitsOutOfOrderAtEntry], or without [GadSettings.advancesOnArtworkExit], it is
 *   visited at the entry and the stops are ordered at once (PM review 2026-10-12).
 * - A curated tour ignores such an artwork: it stays open ([countsOutOfOrder]; PM, Monday "Curated
 *   tours: ignore other artworks", 2026-10-23).
 * - An amenity has no geofence. A detour is reached within 5–10 m for 5 s ([detourGuidanceRules],
 *   [GadSettings]).
 * - After a reached detour, the journey continues only after "Resume your Journey" ([rules]).
 *
 * A new personalised journey is ordered floor by floor and gallery by gallery
 * (`JourneyOrderStrategy.FLOOR_THEN_GALLERY_ROUTED`, or `FLOOR_THEN_GALLERY` when
 * [GadSettings.ordersGalleriesByWalkingTime] is `false`): the stop nearest the visitor first, then the
 * rest of its gallery, then the rest of its floor. Without a fix the first stop stays first, and the first
 * fix within 60 s orders the journey again while no stop is reached. A restored journey keeps its
 * order. After a detour the stops still ahead are ordered again from the visitor. A curated tour
 * keeps the curators' order ([keepsOrder]).
 *
 * Off the route (PM, Monday "Personalized tour: off-route and re-route behaviour" and "Curated tour:
 * off-route and recalculation", 2026-10-26 and 2026-10-28): the visitor gets a warning ([isOffRoute])
 * once they are [GadSettings.offRouteMeters] (12 m) off the leg to the current destination
 * ([checkLegDeviation]), also where a later leg of the journey passes, or off the whole journey
 * (`LeftRoute`), far from it, or off it for too long. The navigator's `LeftRoute` measures against
 * every leg still to walk, so a visitor heading to another artwork got no warning (PM's log of
 * 2026-10-28, 22:39 UTC: 56 m off the leg). The warning comes before any routing: with
 * [io.proximi.gadkit.ProximiMapView.Option.OFF_ROUTE_PROMPT] the navigator routes nothing by itself
 * (`JourneyDeviationPolicy.ASK_APP`). After the warning a personalised journey keeps the leg where the
 * visitor left it; when another open artwork is now the nearest stop, the stops ahead are ordered
 * again from the visitor and the leg leads to it ([checkCloserArtwork]). A curated tour is routed
 * again from the visitor after the warning ([reroutesAfterWarning]). A journey without the prompt is
 * routed again by the navigator (`AUTOMATIC`).
 *
 * The warning stays open for [GadSettings.offRouteWarningDuration], unless the visitor closes it; the
 * routing after it does not close it (PM, 2026-10-27). A visitor on a floor the route does not take
 * for [GadSettings.wrongFloorAfter] is off the route too ([checkWrongFloor]; PM, Monday "Wrong floor to
 * trigger off-route and re-routing", 2026-10-27).
 *
 * An artwork visited out of order moves before the stops still to visit, so the numbers in the list
 * and on the map follow the visit order (PM, Monday "Personalized tour: renumbering artworks",
 * 2026-10-26).
 *
 * [pause] stops the guidance until [resumeFromPause]: no artwork is visited, no warning shows, and the
 * navigator leaves the leg where it is (Nagarro, 2026-10-28).
 *
 * Without [managesProgress] ([io.proximi.gadkit.ProximiMapView.Option.PROGRESS]) the host app moves
 * the journey on: no arrival by geofence, no ordering, and [apply] takes the host app's changes.
 *
 * Skip and the EC04 pick edit the running navigator (`skip`, `activate`), so its
 * route cache, event streams and deviation state are kept. The arrival, out of order, gallery first
 * and "Resume your Journey" each read the journey before they change it, so they run one after
 * another ([serially]).
 *
 * Gallery first, personalised journeys only (PM, Monday "Navigation to a gallery", 2026-10-15): a
 * leg to an artwork in another gallery ends at that gallery's POI ([GalleryFirstRouting]). When a fix
 * is in that gallery, the leg is drawn again from the visitor, to the artwork ([checkGalleryFirst]).
 * Main thread only.
 */
internal class VisitController(
    val session: ProximiioMapSession,
    journey: CoreJourney,
    /**
     * Stores the journey after each change; `null` after Journey Complete. `ProximiMap` writes
     * [VisitStore], `ProximiMapView` the host app's journey.
     */
    private val persist: (CoreJourney?) -> Unit,
    private val scope: CoroutineScope,
    /** The visitor's accessibility needs for every leg ([io.proximi.gadkit.map.RouteAccessibility]). */
    accessibility: RouteAccessibility = RouteAccessibility.NONE,
    /** The drawable resource name of the app's glyph for an amenity, for the detour marker. */
    private val amenityIconName: (VenuePlace) -> String? = { null },
    autoAdvance: Boolean = false,
    /**
     * A curated tour keeps the curators' order: no ordering at the start, on the first fix, after
     * a detour or after an artwork visited out of order ([JourneyKind.keepsOrder]). So does a
     * journey whose progress the host app manages.
     */
    keepsOrder: Boolean = false,
    /**
     * Whether an artwork further on that the visitor stays at counts as visited
     * ([JourneyKind.countsOutOfOrder]). Off in a curated tour: only the active artwork is visited.
     */
    private val countsOutOfOrder: Boolean = true,
    /**
     * [io.proximi.gadkit.ProximiMapView.Option.PROGRESS]: artwork geofences mark stops visited, the
     * journey is ordered, and a stop whose POI is deleted is skipped. Off, only [apply] changes the
     * stops.
     */
    private val managesProgress: Boolean = true,
    /**
     * [io.proximi.gadkit.ProximiMapView.Option.OFF_ROUTE_PROMPT]: the navigator routes nothing by
     * itself; the warning comes first (`ASK_APP`). Off, the navigator routes again from the visitor.
     */
    private val deviationPolicy: JourneyDeviationPolicy = JourneyDeviationPolicy.ASK_APP,
    /** Times and distances: arrival, out-of-order dwell, off route, route snap, journey order. */
    private val settings: GadSettings = GadSettings.STANDARD,
) {
    private val keepsOrder = keepsOrder || !managesProgress

    /** The rule for the order of the stops ([GadSettings.ordersGalleriesByWalkingTime]). */
    private val orderStrategy = VisitController.orderStrategy(settings)

    /**
     * A reached detour waits for "Resume your Journey" (PM review 2026-10-12). Artworks are never
     * reached by the navigator ([artworkGuidanceRules]); the controller marks them done after the
     * geofence enter event. The debug flag `visitAutoAdvance` leaves a detour 5 m away instead.
     */
    private val rules = JourneyRules(
        advance = if (autoAdvance) {
            JourneyRules.Advance.OnDeparture(JourneyConstants.AUTO_ADVANCE_ON_DEPARTURE_METERS)
        } else {
            JourneyRules.Advance.Manual
        },
    )

    /** The navigator's legs (gallery first). */
    private val galleryRouting = GalleryFirstRouting(ProximiioJourneyRouting(checkNotNull(session.sdk)), settings.routeToStopMaximumMeters, settings.routeFromOriginMaximumMeters)

    /** Guidance on a leg to an artwork ([artworkGuidanceRules]). */
    private val artworkGuidanceRules = artworkGuidanceRules(settings)

    val navigator = JourneyNavigator(session, journey.toMap(), rules, artworkGuidanceRules, routing = galleryRouting).apply {
        drawsNextLegWhileReached = settings.advancesOnArtworkExit
    }

    private val mutableGalleryAhead = MutableStateFlow<GalleryDestination?>(null)

    /** The gallery the leg to the active artwork ends at, or `null` when it ends at the artwork. */
    val galleryAhead: StateFlow<GalleryDestination?> = mutableGalleryAhead.asStateFlow()

    /** The journey changes that run one after another ([serially]). */
    private val journeyEdits = Mutex()

    /** Guidance on a leg to an amenity detour ([detourGuidanceRules]). */
    private val detourGuidanceRules = detourGuidanceRules(settings)

    private val outOfOrderDwellMillis = settings.outOfOrderDwell.inWholeMilliseconds

    private var routeOptions: RouteOptions = accessibility.routeOptions

    /**
     * New accessibility needs: the navigator routes the current leg again. The same needs change
     * nothing.
     */
    suspend fun setAccessibility(accessibility: RouteAccessibility) {
        routeOptions = accessibility.routeOptions
        navigator.setRouteOptions(routeOptions)
    }

    init {
        // The dot is drawn on the route within this distance. Display only: guidance and arrival
        // use the real position (PM review 2026-10-12).
        session.routeSnapping = RouteSnapRules(maximumOffsetMeters = settings.routeSnapMeters)
        // "Looks like you've left your route": this far from the route, or off it this long.
        navigator.deviationRules = navigator.deviationRules.copy(
            leftRouteMeters = settings.offRouteMeters,
            offRouteTooLongSeconds = settings.offRouteAfter.inWholeMilliseconds / 1000.0,
        )
    }

    /** `remote_id` of each artwork POI, by POI id. Set once the venue's features are loaded. */
    var artworkRemoteIds: Map<String, String> = emptyMap()

    /** Artwork geofence shapes, by `remote_id`. Set once the venue's geofences are loaded. */
    var artworkAreas: Map<String, List<ArtworkArea>> = emptyMap()

    /**
     * The geofences the SDK counts the visitor inside (`Proximiio.enteredGeofenceIds`), kept up to
     * date from the geofence events.
     */
    var enteredGeofenceIds: Set<String> = emptySet()

    /** Called when the visitor is at an artwork of the journey. [VisitViewModel] reports [VisitEvent.ArtworkArrived]. */
    var onArtworkArrived: ((JourneyStop) -> Unit)? = null

    /**
     * Called after an artwork stop is done, with the reason. The stop is read after `markDone`.
     * [VisitViewModel] reports [VisitEvent.ArtworkVisited].
     */
    var onArtworkVisited: ((JourneyStop, ArtworkVisitCause) -> Unit)? = null

    /** Stops whose move to the next artwork is scheduled. */
    private val scheduledDone = mutableSetOf<String>()

    /**
     * With [GadSettings.advancesOnArtworkExit]: the active artwork whose geofence the visitor entered.
     * The journey moves on when they leave it.
     */
    private var arrivedStopId: String? = null

    /**
     * The first fix more than [GadSettings.artworkVisitedMeters] from the geofence of [arrivedStopId]
     * (ms); `null` while the visitor is closer.
     */
    private var outsideArrivedSince: Long? = null

    /** The artwork the navigator holds reached (`markReached`) and that is not yet reported visited. */
    private var reachedStopId: String? = null

    /** Time inside the geofence of each open artwork that is not the active stop. */
    private val outOfOrderDwell = GeofenceDwell()

    /** Out-of-order artworks being marked visited, or being made the current stop. */
    private val markingOutOfOrder = mutableSetOf<String>()

    /**
     * The artwork that became the current stop because the visitor entered it out of order. The
     * stops ahead are ordered again from the visitor when it is visited.
     */
    private var outOfOrderArrivedId: String? = null

    private val mutableOffRoute = MutableStateFlow(false)

    /**
     * `true` for [GadSettings.offRouteWarningDuration] after `LeftRoute`, `FarFromRoute`,
     * `OffRouteTooLong` or a wrong floor, until the visitor closes the warning, resumes the journey or
     * adds a stop.
     */
    val isOffRoute: StateFlow<Boolean> = mutableOffRoute.asStateFlow()
    private var offRouteWarning: Job? = null

    /**
     * From `LeftRoute`, `FarFromRoute` or `OffRouteTooLong` until the visitor is back on the route or
     * a new leg starts. The warning can be closed meanwhile.
     */
    private var isOffRouteEpisode = false
        set(value) {
            field = value
            if (!value) isLegOffRouteEpisode = false
        }

    /** The episode started off the leg ([checkLegDeviation]); it ends when the visitor is back on it. */
    private var isLegOffRouteEpisode = false

    /** Since when the fixes have been on a floor the route does not take (milliseconds). */
    private var wrongFloorSince: Long? = null

    /** The journey was routed again for the wrong floor the visitor is still on. */
    private var isRoutedForWrongFloor = false
    private var lastCloserArtworkCheck = 0L

    private val mutablePaused = MutableStateFlow(false)

    /** [pause] until [resumeFromPause]. */
    val isPaused: StateFlow<Boolean> = mutablePaused.asStateFlow()

    private val mutableRerouting = MutableStateFlow(false)
    private var reroutes = 0

    /**
     * `true` while the controller routes the journey again: the order after an artwork visited out of
     * order or a closer artwork, the leg from the visitor, the end of a detour ("Rerouting you…").
     */
    val isRerouting: StateFlow<Boolean> = mutableRerouting.asStateFlow()

    private val mutablePassingStopId = MutableStateFlow<String?>(null)

    /**
     * The stop the navigator walks to for a moment while an artwork visited out of order moves ahead.
     * The screen reports no `NextStop` for it.
     */
    val passingStopId: StateFlow<String?> = mutablePassingStopId.asStateFlow()

    /** `true` after [end]: the visit is over and nothing is saved any more. */
    private var hasEnded = false

    private var isStarted = false

    /** `true` once `navigator.start()` has returned. */
    private var isNavigating = false

    /** The host app's latest journey while [apply] runs. */
    private var pendingTarget: CoreJourney? = null
    private var isApplying = false

    /** Stops whose POI is no longer a place, reported once ([venueChanged]). */
    private val reportedWithoutPlace = mutableSetOf<String>()

    private var eventsJob: Job? = null
    private val doneJobs = mutableListOf<Job>()

    val journey: CoreJourney get() = navigator.journey.value.toCore()

    /** Starts the journey once. A recreated screen calls it again. */
    suspend fun start() {
        if (isStarted) return
        isStarted = true
        navigator.deviationPolicy = if (mutablePaused.value) JourneyDeviationPolicy.ASK_APP else deviationPolicy
        navigator.setRouteOptions(routeOptions)
        var isOrderedWithoutFix = false
        if (!keepsOrder && navigator.journey.value.stops.all { it.state == JourneyStop.State.PENDING }) {
            waitForFix(settings.firstFixWait.inWholeMilliseconds)
            isOrderedWithoutFix = session.position.value == null
            proposedOrder()?.takeIf { it.isImprovement }?.let { apply(it) }
        }
        navigator.start()
        isNavigating = true
        applyPendingTarget()
        eventsJob = scope.launch { navigator.events.collect(::handle) }
        if (isOrderedWithoutFix) doneJobs += scope.launch { orderOnFirstFix() }
    }

    private suspend fun waitForFix(limitMillis: Long) {
        withTimeoutOrNull(limitMillis) { session.position.first { it != null } }
    }

    /** Orders the journey from the first fix while no stop is reached, done or skipped. */
    private suspend fun orderOnFirstFix() {
        waitForFix(settings.lateOrderWindow.inWholeMilliseconds)
        if (session.position.value == null || navigator.hasArrived) return
        if (!navigator.journey.value.stops.all { it.state == JourneyStop.State.PENDING || it.state == JourneyStop.State.ACTIVE }) return
        val proposal = navigator.proposeOrder(JourneyOrderOrigin.VISITOR, strategy = orderStrategy) ?: return
        if (proposal.isImprovement) apply(proposal)
    }

    /** The floor-then-gallery order from the visitor's fix, or from the first stop without a fix. */
    private suspend fun proposedOrder(): JourneyOrderProposal? =
        navigator.proposeOrder(JourneyOrderOrigin.VISITOR, strategy = orderStrategy)
            ?: navigator.proposeOrder(JourneyOrderOrigin.ACTIVE_STOP, strategy = orderStrategy)

    /** Orders the stops still ahead from the visitor, floor by floor and gallery by gallery. */
    private suspend fun orderRemaining() {
        if (keepsOrder) return
        val proposal = navigator.proposeOrder(JourneyOrderOrigin.VISITOR, strategy = orderStrategy) ?: return
        if (proposal.isImprovement) apply(proposal)
    }

    private suspend fun apply(proposal: JourneyOrderProposal) {
        val applied = navigator.apply(proposal)
        FieldLog.log("journey order: ${if (applied) "applied" else "stale"} · ${proposal.order.joinToString()}")
        save()
    }

    private fun handle(event: JourneyEvent) {
        refreshGalleryAhead()
        when (event) {
            is JourneyEvent.StopReached ->
                if (event.stop.kind != JourneyStop.Kind.DETOUR && !settings.advancesOnArtworkExit) {
                    scheduleDone(event.stop.id, settings.artworkAdvanceDelay, ArtworkVisitCause.ADVANCE_DELAY)
                }
            is JourneyEvent.DetourEnded -> if (event.completed) doneJobs += scope.launch { serially { rerouting { orderRemaining() } } }
            is JourneyEvent.LeftRoute, is JourneyEvent.FarFromRoute, is JourneyEvent.OffRouteTooLong -> offRouteEpisode()
            is JourneyEvent.LegChanged -> {
                isOffRouteEpisode = false
                reportReachedStopDone()
            }
            JourneyEvent.ReturnedToRoute, is JourneyEvent.Rerouted -> isOffRouteEpisode = false
            JourneyEvent.JourneyFinished -> reportReachedStopDone()
            else -> Unit
        }
    }

    /** The navigator reported the visitor off the route: the warning, and for a curated tour the leg from the visitor. */
    private fun offRouteEpisode() {
        if (mutablePaused.value) return
        val startsEpisode = !isOffRouteEpisode
        isOffRouteEpisode = true
        showOffRouteWarning()
        if (startsEpisode && reroutesAfterWarning) rerouteFromVisitor()
    }

    /**
     * Called on every guidance update. The visitor [GadSettings.offRouteMeters] off the leg to the
     * current destination (`RouteGuidance.isOffRoute`) gets the warning, as for the navigator's
     * `LeftRoute`, also where a later leg of the journey passes (PM, Monday "Personalized tour:
     * off-route and re-route behaviour", 2026-10-28). Back within two thirds of that distance the
     * episode ends. Only with the warning (`ASK_APP`); not while paused, during a detour or on a floor
     * without positioning.
     */
    fun checkLegDeviation(guidance: RouteGuidance?, isFloorGuideActive: Boolean) {
        if (deviationPolicy != JourneyDeviationPolicy.ASK_APP || !isNavigating || mutablePaused.value || isFloorGuideActive ||
            navigator.detourStop != null || navigator.hasArrived || guidance == null || guidance.hasArrived
        ) {
            return
        }
        when (legDeviation(guidance.isOffRoute, guidance.offRouteMeters, settings.offRouteMeters, isOffRouteEpisode, isLegOffRouteEpisode)) {
            LegDeviation.NONE -> Unit
            LegDeviation.ENDS -> isOffRouteEpisode = false
            LegDeviation.WARNS -> {
                FieldLog.log(String.format(Locale.ROOT, "off route: %.1f m off the leg to %s", guidance.offRouteMeters, navigator.activeStop?.title ?: "-"))
                isOffRouteEpisode = true
                isLegOffRouteEpisode = true
                showOffRouteWarning()
                if (reroutesAfterWarning) rerouteFromVisitor()
            }
        }
    }

    /** What one guidance update off or on the leg does ([legDeviation]). */
    internal enum class LegDeviation {
        NONE,

        /** A new episode: the warning shows. */
        WARNS,

        /** The visitor is back on the leg: the episode [checkLegDeviation] started ends. */
        ENDS,
    }

    /**
     * Called on every fix. Personalised journeys off the route: when the nearest open artwork is no
     * longer the active stop, the stops ahead are ordered again from the visitor and the leg leads to
     * that artwork. At most every 5 s.
     */
    fun checkCloserArtwork() {
        if (!managesProgress || keepsOrder || !isNavigating || mutablePaused.value || !isOffRouteEpisode || navigator.detourStop != null || navigator.hasArrived) return
        val active = navigator.activeStop ?: return
        val now = System.currentTimeMillis()
        if (now - lastCloserArtworkCheck < CLOSER_ARTWORK_INTERVAL_MILLIS) return
        lastCloserArtworkCheck = now
        doneJobs += scope.launch {
            serially {
                if (!isOffRouteEpisode || navigator.activeStop?.id != active.id) return@serially
                val proposal = navigator.proposeOrder(JourneyOrderOrigin.VISITOR, strategy = orderStrategy) ?: return@serially
                val first = proposal.order.firstOrNull()
                if (!proposal.isImprovement || first == null || first == active.id) return@serially
                FieldLog.log("off route: $first is nearer than ${active.title} · journey ordered again")
                isOffRouteEpisode = false
                rerouting { apply(proposal) }
            }
        }
    }

    /**
     * Called on every fix. A visitor on a floor the route ahead does not take for
     * [GadSettings.wrongFloorAfter] is off the route: the warning shows, and the leg is drawn again
     * from the visitor. A personalised journey is ordered again first, so the leg leads to the nearest
     * open artwork; a curated tour keeps its next stop. Once per wrong floor: the next check starts
     * when a fix is on a floor of the route again. Off on a floor without positioning
     * ([isFloorGuideActive]).
     */
    fun checkWrongFloor(isFloorGuideActive: Boolean) {
        val level = session.position.value?.floor?.level
        val legs = session.routeRenderer?.geometry?.legs
        if (!isNavigating || mutablePaused.value || isFloorGuideActive || navigator.activeStop == null || navigator.hasArrived ||
            level == null || legs == null || isOnRouteFloor(level, legs)
        ) {
            wrongFloorSince = null
            isRoutedForWrongFloor = false
            return
        }
        if (isRoutedForWrongFloor) return
        val now = System.currentTimeMillis()
        val since = wrongFloorSince ?: now
        wrongFloorSince = since
        if (now - since < settings.wrongFloorAfter.inWholeMilliseconds) return
        isRoutedForWrongFloor = true
        wrongFloorSince = null
        FieldLog.log("wrong floor: L${level.roundToInt()} is not on the route · journey routed again from the visitor")
        isOffRouteEpisode = true
        showOffRouteWarning()
        val reorders = managesProgress && !keepsOrder
        doneJobs += scope.launch {
            serially {
                rerouting {
                    if (reorders) {
                        navigator.proposeOrder(JourneyOrderOrigin.VISITOR, strategy = orderStrategy)
                            ?.takeIf { it.isImprovement }
                            ?.let { apply(it) }
                    }
                    navigator.resumeJourney()
                }
                save()
            }
        }
    }

    /**
     * A journey that keeps its order and shows the warning (`ASK_APP`, a curated tour) is routed
     * again from the visitor after the warning. A personalised journey keeps its leg.
     */
    private val reroutesAfterWarning: Boolean
        get() = deviationPolicy == JourneyDeviationPolicy.ASK_APP && keepsOrder

    /** Draws the leg again from the visitor, after the warning. */
    private fun rerouteFromVisitor() {
        FieldLog.log("off route: warning shown · leg drawn again from the visitor")
        doneJobs += scope.launch {
            serially {
                if (!isOffRouteEpisode) return@serially
                rerouting { navigator.resumeJourney() }
                save()
            }
        }
    }

    /**
     * Opens the off-route warning for [GadSettings.offRouteWarningDuration]. A new episode while it
     * is open starts the time again.
     */
    private fun showOffRouteWarning() {
        mutableOffRoute.value = true
        offRouteWarning?.cancel()
        offRouteWarning = scope.launch {
            delay(settings.offRouteWarningDuration)
            mutableOffRoute.value = false
        }
    }

    private fun closeOffRouteWarning() {
        offRouteWarning?.cancel()
        offRouteWarning = null
        mutableOffRoute.value = false
    }

    /** Runs [body] with [isRerouting] on. */
    private suspend fun <T> rerouting(body: suspend () -> T): T {
        reroutes += 1
        mutableRerouting.value = true
        try {
            return body()
        } finally {
            reroutes -= 1
            mutableRerouting.value = reroutes > 0
        }
    }

    /**
     * The visitor entered an artwork geofence. With [GadSettings.advancesOnArtworkExit] the active
     * artwork moves on when the visitor leaves it, else after [GadSettings.artworkAdvanceDelay]. The
     * geofence of any other artwork is ignored, and every artwork geofence is ignored during a
     * detour.
     */
    fun artworkGeofenceEntered(remoteId: String?) {
        if (!managesProgress || mutablePaused.value || navigator.detourStop != null) return
        val id = ArtworkArrival.activeStopId(remoteId, journey, artworkRemoteIds) ?: return
        if (!settings.advancesOnArtworkExit) {
            if (id in scheduledDone) return
            FieldLog.log("artwork geofence arrival: $id remote ${remoteId ?: "-"}")
            doneStop(id)?.let { onArtworkArrived?.invoke(it) }
            return scheduleDone(id, settings.artworkAdvanceDelay, ArtworkVisitCause.ADVANCE_DELAY)
        }
        if (arrivedStopId == id) return
        outsideArrivedSince = null
        arrivedStopId = id
        FieldLog.log("artwork geofence arrival: $id remote ${remoteId ?: "-"} · next stop at the exit")
        doneJobs += scope.launch {
            serially {
                if (arrivedStopId != id || navigator.activeStop?.id != id) return@serially
                // A detour turns it off ([detour]).
                navigator.drawsNextLegWhileReached = true
                if (!navigator.markReached(id)) return@serially
                reachedStopId = id
                doneStop(id)?.let { onArtworkArrived?.invoke(it) }
                // A next leg from inside the next artwork's gallery ends at the artwork. Without the mark,
                // `checkGalleryFirst` routed that leg again at the first fix after the exit (iOS 1.31.1).
                val next = navigator.upcomingStop
                val position = session.position.value
                if (next != null && position != null) {
                    val gallery = galleryRouting.gallery(next.coordinate)
                    val point = Coordinate(position.coordinate.latitude, position.coordinate.longitude)
                    if (gallery != null && gallery.gallery.contains(point, position.floor?.level)) galleryRouting.markReached(next.coordinate)
                }
                FieldLog.log("artwork reached: $id · route to ${navigator.upcomingStop?.title ?: "-"}")
                save()
            }
        }
    }

    /**
     * Reports the reached artwork visited once the navigator has moved past it without [scheduleDone]:
     * a detour or a chosen stop makes a reached stop done.
     */
    private fun reportReachedStopDone() {
        val id = reachedStopId ?: return
        val stop = doneStop(id) ?: return
        if (stop.state != JourneyStop.State.DONE) return
        reachedStopId = null
        FieldLog.log("artwork left (the journey moved on): $id")
        onArtworkVisited?.invoke(stop, ArtworkVisitCause.JOURNEY_MOVED_ON)
    }

    /** The visitor left the active artwork they arrived at: the journey moves to the next stop. */
    private fun leaveArrivedArtwork(reason: String, cause: ArtworkVisitCause) {
        val id = arrivedStopId ?: return
        arrivedStopId = null
        outsideArrivedSince = null
        if (navigator.activeStop?.id != id) return
        FieldLog.log("artwork left ($reason): $id · next stop")
        scheduleDone(id, Duration.ZERO, cause)
    }

    /**
     * Checks the fix against the geofences of the open artworks on the visitor's floor, and against
     * [enteredGeofenceIds]. Inside the active artwork's geofence: the arrival, which also covers a
     * leg that starts inside it, where the SDK sends no enter event. Inside another open artwork's
     * geofence for [GadSettings.outOfOrderDwell]: that artwork is visited out of order, except in a
     * curated tour ([countsOutOfOrder]). During a detour to an amenity no artwork counts (PM review
     * 2026-10-12).
     */
    fun checkArtworkGeofences() {
        // Before the start the journey is still being ordered.
        if (!managesProgress || !isNavigating) return
        if (navigator.detourStop != null || mutablePaused.value) {
            outOfOrderDwell.update(emptySet(), System.currentTimeMillis(), outOfOrderDwellMillis)
            return
        }
        val position = session.position.value ?: return
        val point = Coordinate(position.coordinate.latitude, position.coordinate.longitude)
        val level = position.floor?.level
        val activeId = navigator.activeStop?.id
        if (settings.advancesOnArtworkExit && arrivedStopId == null && navigator.hasArrived) {
            // A journey restored with the artwork reached: it moves on when the visitor is outside.
            arrivedStopId = activeId
            reachedStopId = activeId
        }
        var inside = mutableSetOf<String>()
        // Metres from the border of the active artwork's geofence; 0 inside it.
        var activeDistance = Double.POSITIVE_INFINITY
        // A curated tour counts no artwork out of order, but the next artwork's geofence still ends
        // the visit of the active one (GAD PM, 2026-11-15).
        val upcomingId = if (navigator.hasArrived) navigator.upcomingStop?.id else null
        var isInsideUpcoming = false
        for (stop in navigator.journey.value.stops) {
            if (stop.kind == JourneyStop.Kind.DETOUR) continue
            if (stop.state != JourneyStop.State.PENDING && stop.state != JourneyStop.State.ACTIVE && stop.state != JourneyStop.State.REACHED) continue
            val remoteId = artworkRemoteIds[stop.poiId ?: stop.id] ?: continue
            val areas = artworkAreas[remoteId] ?: continue
            val stopLevel = stop.floor?.level
            if (!VenueLevel.matches(stopLevel, level)) continue
            if (stop.id == activeId) activeDistance = areas.minOfOrNull { it.distanceTo(point) } ?: Double.POSITIVE_INFINITY
            if (areas.none { it.id in enteredGeofenceIds || it.contains(point) }) continue
            if (stop.id == activeId) {
                artworkGeofenceEntered(remoteId)
            } else if (countsOutOfOrder) {
                inside += stop.id
            } else if (stop.id == upcomingId) {
                isInsideUpcoming = true
            }
        }
        val arrived = arrivedStopId
        if (arrived != null) {
            when {
                // Skipped, reordered or visited otherwise.
                arrived != activeId -> {
                    arrivedStopId = null
                    outsideArrivedSince = null
                    if (outOfOrderArrivedId == arrived) outOfOrderArrivedId = null
                }
                // In another open artwork: the visitor has moved on. That artwork counts from the next
                // fix, after the journey moved on.
                inside.isNotEmpty() || isInsideUpcoming -> {
                    leaveArrivedArtwork("in another artwork", ArtworkVisitCause.ENTERED_ANOTHER_ARTWORK)
                    inside = mutableSetOf()
                }
                ArtworkArrival.isBeyondVisit(activeDistance, settings.artworkVisitedMeters) -> {
                    val now = System.currentTimeMillis()
                    val since = outsideArrivedSince ?: now.also { outsideArrivedSince = it }
                    if (now - since >= settings.artworkVisitedSeconds.inWholeMilliseconds) {
                        leaveArrivedArtwork(
                            "more than ${settings.artworkVisitedMeters} m away for ${settings.artworkVisitedSeconds.inWholeSeconds} s",
                            ArtworkVisitCause.LEFT,
                        )
                    }
                }
                else -> outsideArrivedSince = null
            }
        }
        for (id in outOfOrderDwell.update(inside, System.currentTimeMillis(), outOfOrderDwellMillis)) {
            if (arrivesOutOfOrder(settings)) arriveOutOfOrder(id) else markOutOfOrder(id)
        }
    }

    /**
     * Makes a pending artwork the visitor entered out of order the current stop. The next check of
     * the fix finds the visitor inside the active artwork's geofence: the artwork is reached, and it
     * is visited as any reached artwork (GAD PM, 2026-11-16: an artwork entered out of order is not
     * visited at the entry; iOS 1.48.0). An artwork the visitor is still at is left first
     * ([leaveArrivedArtwork], "in another artwork"); this runs after it.
     */
    private fun arriveOutOfOrder(stopId: String) {
        if (!markingOutOfOrder.add(stopId)) return
        doneJobs += scope.launch {
            serially {
                try {
                    // `arrivedStopId`: another artwork entered at the same fix is already the current stop.
                    if (mutablePaused.value || navigator.detourStop != null || navigator.hasArrived || arrivedStopId != null) return@serially
                    if (navigator.journey.value.stops.firstOrNull { it.id == stopId }?.state != JourneyStop.State.PENDING) return@serially
                    FieldLog.log("artwork entered out of order: $stopId · it is the current stop")
                    val isActive = rerouting {
                        // The artwork moves before the stops still to visit: the numbers follow the
                        // visit order, and the navigator leads to it.
                        navigator.move(stopId, toIndex = 0)
                        navigator.activeStop?.id == stopId
                    }
                    if (!isActive) return@serially
                    outOfOrderArrivedId = stopId
                    save()
                    checkArtworkGeofences()
                } finally {
                    markingOutOfOrder.remove(stopId)
                }
            }
        }
    }

    /**
     * Sets the gallery of each artwork among [places] for gallery first, from `metadata.gallery_id`
     * ([GalleryDestination]). An artwork outside its gallery ([GadSettings.galleryFirstMarginMeters])
     * has none. Off with [isEnabled] false.
     */
    fun setGalleryFirst(isEnabled: Boolean, places: List<VenuePlace>, destinations: Map<String, GalleryDestination>) {
        val galleries = HashMap<GalleryFirstRouting.Key, GalleryDestination>()
        val outside = mutableListOf<String>()
        for (place in places) {
            if (!place.isArtwork) continue
            val destination = place.galleryId?.let { destinations[it] } ?: continue
            if (!GalleryFirstRouting.isInGallery(place, destination, settings.galleryFirstMarginMeters)) {
                outside += place.title
                continue
            }
            galleries[GalleryFirstRouting.Key(MapCoordinate(place.coordinate.latitude, place.coordinate.longitude))] = destination
        }
        if (outside.isNotEmpty()) {
            FieldLog.log("gallery first: ${outside.size} artworks outside their gallery, routes end at the artwork: ${outside.sorted().joinToString()}")
        }
        galleryRouting.update(isEnabled, galleries)
        refreshGalleryAhead()
    }

    /**
     * Called on every fix. A fix in the gallery that the leg to the active artwork ends at: the leg is
     * drawn again from the visitor, to the artwork.
     */
    fun checkGalleryFirst() {
        try {
            if (!isNavigating || mutablePaused.value || navigator.detourStop != null || navigator.hasArrived) return
            val stop = navigator.activeStop ?: return
            val gallery = galleryRouting.gallery(stop.coordinate) ?: return
            val position = session.position.value ?: return
            if (!gallery.gallery.contains(Coordinate(position.coordinate.latitude, position.coordinate.longitude), position.floor?.level)) return
            galleryRouting.markReached(stop.coordinate)
            FieldLog.log("gallery first: in ${gallery.gallery.name} · leg to ${stop.title}")
            doneJobs += scope.launch {
                serially { rerouting { navigator.resumeJourney() } }
            }
        } finally {
            refreshGalleryAhead()
        }
    }

    /**
     * Runs [edit] after the journey changes started before it. Out of order once proposed an order
     * before it marked an artwork visited, and that order made the artwork active again (field test
     * 2026-10-19). [edit] must not call [serially].
     */
    private suspend fun serially(edit: suspend () -> Unit) {
        journeyEdits.withLock { edit() }
    }

    /** No gallery during a detour or at a reached artwork: the card names the stop. */
    private fun refreshGalleryAhead() {
        mutableGalleryAhead.value = if (navigator.detourStop == null && !navigator.hasArrived) navigator.activeStop?.let { galleryRouting.gallery(it.coordinate) } else null
    }

    /** Marks a pending artwork visited out of order and orders the stops still ahead again from the visitor. */
    private fun markOutOfOrder(stopId: String) {
        if (!markingOutOfOrder.add(stopId)) return
        doneJobs += scope.launch {
            serially {
                try {
                    if (navigator.journey.value.stops.firstOrNull { it.id == stopId }?.state != JourneyStop.State.PENDING) return@serially
                    FieldLog.log("artwork visited out of order: $stopId")
                    val activeStopId = navigator.activeStop?.id
                    rerouting {
                        // The artwork moves before the stops still to visit: the numbers follow the
                        // visit order. The navigator walks to it for a moment.
                        mutablePassingStopId.value = stopId
                        navigator.move(stopId, toIndex = 0)
                        val isDone = navigator.markDone(stopId)
                        mutablePassingStopId.value = null
                        if (isDone) {
                            doneStop(stopId)?.let {
                                onArtworkArrived?.invoke(it)
                                onArtworkVisited?.invoke(it, ArtworkVisitCause.OUT_OF_ORDER)
                            }
                        }
                        orderRemaining()
                        // The navigator draws a new leg only when the active stop changes. Without a
                        // change the old leg, from where the visitor was, would stay (PM "Route drawing
                        // when going through artworks in wrong order").
                        if (activeStopId != null && navigator.activeStop?.id == activeStopId) {
                            FieldLog.log("leg to $activeStopId drawn again from the visitor")
                            navigator.resumeJourney()
                        }
                    }
                    save()
                } finally {
                    markingOutOfOrder.remove(stopId)
                }
            }
        }
    }

    /** Marks [stopId] visited after [after] if it is still the active stop. The leg to the next artwork is drawn. */
    private fun scheduleDone(stopId: String, after: Duration, cause: ArtworkVisitCause) {
        if (!scheduledDone.add(stopId)) return
        doneJobs += scope.launch {
            try {
                delay(after)
            } catch (cancelled: CancellationException) {
                scheduledDone.remove(stopId)
                throw cancelled
            }
            serially {
                scheduledDone.remove(stopId)
                if (navigator.activeStop?.id != stopId) return@serially
                // Reported here, not by [reportReachedStopDone] on the leg change.
                if (reachedStopId == stopId) reachedStopId = null
                val changed = navigator.markDone(stopId)
                save()
                if (changed) doneStop(stopId)?.let { onArtworkVisited?.invoke(it, cause) }
                if (!changed) return@serially
                if (outOfOrderArrivedId == stopId) {
                    // Entered out of order: the stops ahead are ordered from the visitor, as they
                    // were at the entry before 1.48.0.
                    outOfOrderArrivedId = null
                    rerouting { orderRemaining() }
                } else {
                    orderFromTheVisitorsGallery()
                }
            }
        }
    }

    /**
     * Called after an artwork is marked visited. A personalised journey whose next stop is in another
     * gallery than the one the visitor stands in, while that gallery has an artwork still to visit: the
     * stops ahead are ordered again from the visitor, so the leg leads to an artwork in their gallery
     * (GAD PM, Monday "Re-routing going from gallery 104 to 102", 2026-11-15; iOS 1.46.0). The route
     * from the visitor starts on the leg, so the off-route order never ran there.
     */
    private suspend fun orderFromTheVisitorsGallery() {
        if (!settings.ordersFromVisitorGallery || keepsOrder || navigator.detourStop != null || navigator.hasArrived) return
        val next = navigator.activeStop ?: return
        val position = session.position.value ?: return
        val later = navigator.journey.value.stops.filter { it.state == JourneyStop.State.PENDING && it.id != next.id }.map { it.coordinate }
        val origin = Coordinate(position.coordinate.latitude, position.coordinate.longitude)
        if (!galleryRouting.isInGalleryOfLaterArtwork(origin, position.floor?.level, next.coordinate, later)) return
        val proposal = navigator.proposeOrder(JourneyOrderOrigin.VISITOR, strategy = orderStrategy) ?: return
        val first = proposal.order.firstOrNull()
        if (!proposal.isImprovement || first == null || first == next.id) return
        FieldLog.log("visitor in another gallery: $first is nearer than ${next.title} · journey ordered again")
        rerouting { apply(proposal) }
    }

    private fun doneStop(stopId: String): JourneyStop? = navigator.journey.value.stops.firstOrNull { it.id == stopId }

    suspend fun skip(stopId: String) {
        // Every skip in the field log (iOS 1.46.1).
        val active = navigator.activeStop
        FieldLog.log("skip $stopId: ${doneStop(stopId)?.state?.name?.lowercase() ?: "-"} · active ${active?.id ?: "-"} ${active?.state?.name?.lowercase() ?: "-"}")
        if (!navigator.skip(stopId)) {
            FieldLog.log("skip $stopId: refused")
            return
        }
        save()
    }

    /**
     * Takes a change of the venue data into the open journey, after the journey changes started
     * before it ([serially]). Returns the stops whose POI is no longer a place, each once.
     * - A planned stop that is not reached or done takes the title, coordinate, floor and gallery of
     *   its POI (`JourneyNavigator.replace`). A moved stop being walked to is routed again.
     * - A stop whose POI is no longer a place is skipped with [managesProgress], so `ProximiMapView`
     *   writes it to the host app's journey as skipped. Without [managesProgress] it stays; the host
     *   app decides. When the journey screen of `ProximiMap` closes, the stored journey drops it
     *   ([VisitPlan.reconciled]).
     * - With [routeNetworkChanged] (changed features) the legs are routed again
     *   (`JourneyNavigator.routeNetworkChanged()`). A leg on the line already drawn stays quietly.
     */
    suspend fun venueChanged(places: List<VenuePlace>, galleries: List<Gallery>, routeNetworkChanged: Boolean): List<io.proximi.gadkit.visit.JourneyStop> {
        val withoutPlace = VisitPlan.stopsWithoutPlace(journey, places).filter { it.state != StopState.SKIPPED && it.id !in reportedWithoutPlace }
        reportedWithoutPlace += withoutPlace.map { it.id }
        val skipped = if (managesProgress) withoutPlace else emptyList()
        serially {
            var changed = false
            val current = journey.stops.associateBy { it.id }
            for (stop in VisitPlan.reconciled(journey, places, galleries).stops) {
                if (current[stop.id] == stop) continue
                if (navigator.replace(stop.toMap())) {
                    FieldLog.log("journey: ${stop.title} takes the changed place")
                    changed = true
                }
            }
            for (stop in skipped) {
                FieldLog.log("journey: ${stop.title} is no longer a place · skipped")
                if (navigator.skip(stop.id)) changed = true
            }
            if (routeNetworkChanged) navigator.routeNetworkChanged()
            if (changed) save()
        }
        return withoutPlace
    }

    /**
     * Gives the stops the titles of [places], read in the app's language ([VisitPlan.retitled]; iOS
     * 1.31.3), after the journey changes started before it ([serially]). A new title alone routes
     * nothing. [language] names the language in the field log.
     */
    suspend fun retitle(places: List<VenuePlace>, language: String) {
        serially {
            val current = journey.stops.associateBy { it.id }
            var count = 0
            for (stop in VisitPlan.retitled(journey, places).stops) {
                if (current[stop.id] == stop) continue
                if (navigator.replace(stop.toMap())) count += 1
            }
            if (count == 0) return@serially
            FieldLog.log("journey: $count stop titles in $language")
            save()
        }
    }

    /**
     * Takes the host app's journey on the open screen (`ProximiMapView`): stops added, removed,
     * visited, skipped, reopened and moved. The navigator, its route cache, its event flow and the
     * camera stay. Calls run one after another; a call during another keeps only the latest journey,
     * applied after it. Before the navigator has started, the latest journey waits for the start.
     */
    suspend fun apply(target: CoreJourney) {
        pendingTarget = target
        applyPendingTarget()
    }

    /** Applies the latest journey of the host app once the navigator has started. */
    private suspend fun applyPendingTarget() {
        if (!isNavigating || isApplying) return
        isApplying = true
        try {
            while (true) {
                val next = pendingTarget ?: break
                pendingTarget = null
                performApply(next)
            }
        } finally {
            isApplying = false
        }
    }

    /**
     * Hands the host app's whole journey to the navigator (`JourneyNavigator.update(to)`). The
     * navigator merges it with the list it got last and its own stops: a detour stays, and a step the
     * navigator has already made stays. The navigator was created with the host app's list, so the
     * first call merges against it.
     */
    private suspend fun performApply(target: CoreJourney) {
        // The artworks the host app marks visited with this journey (iOS 1.41.0).
        val done = navigator.journey.value.stops.filter { it.state == JourneyStop.State.DONE }.map { it.id }.toSet()
        val marked = target.stops.filter { it.kind != StopKind.DETOUR && it.state == StopState.DONE && it.id !in done }.map { it.id }
        // Reported below, not by [reportReachedStopDone] on the leg change.
        if (reachedStopId != null && reachedStopId in marked) reachedStopId = null
        val taken = navigator.update(to = target.toMap())
        for (id in marked) {
            val stop = doneStop(id) ?: continue
            if (stop.state != JourneyStop.State.DONE) continue
            FieldLog.log("artwork visited (the host app): $id")
            onArtworkVisited?.invoke(stop, ArtworkVisitCause.APP)
        }
        FieldLog.log("journey applied · ${target.stops.size} stops · ${if (taken) "taken" else "not taken: two stops with one id"}")
    }

    /** Makes [stopId] the next stop (EC04a: the visitor picked the artwork they are at). */
    suspend fun activate(stopId: String) {
        if (navigator.activeStop?.id == stopId) return
        navigator.activate(stopId)
        save()
    }

    /**
     * Inserts [place] as a detour before the active stop. The journey continues after it. The map
     * marker shows the app's glyph for the amenity (a drawable resource name). The style's amenity
     * sprite is a filled circle and drew a white disk once tinted (PM review 2026-10-12). The navigator
     * applies guidance rules to each new leg: the detour leg is reached by distance, the legs after
     * it by the artwork geofence only.
     *
     * A place that is already a stop of the journey (a visited artwork) gets the stop id
     * `detour:<poi id>`, so the journey has no two stops with one id. Returns the id of the detour stop.
     */
    suspend fun detour(place: VenuePlace): String {
        var stop = VisitPlan.stop(place).toMap().copy(iconName = amenityIconName(place))
        if (navigator.journey.value.stops.any { it.id == stop.id }) stop = stop.copy(id = "detour:${place.id}")
        closeOffRouteWarning()
        navigator.guidanceRules = detourGuidanceRules
        // At the reached amenity the route waits for "Resume your Journey"; the next artwork's arrival
        // turns the next leg on again.
        navigator.drawsNextLegWhileReached = false
        navigator.detour(stop)
        navigator.guidanceRules = artworkGuidanceRules
        return stop.id
    }

    /** Drops the detour without recording it, and the leg to the next stop follows. */
    suspend fun cancelDetour() {
        serially {
            navigator.cancelDetour()
            save()
        }
    }

    /**
     * "Resume your Journey" after a detour. A detour reached is recorded as visited; one not reached
     * is dropped. After a detour the stops still ahead are ordered again from the visitor. The leg is
     * drawn from here.
     */
    suspend fun resumeJourney() {
        closeOffRouteWarning()
        serially {
            val hadDetour = navigator.detourStop != null
            rerouting {
                navigator.resumeJourney()
                if (hadDetour) orderRemaining()
            }
            save()
        }
    }

    /**
     * Pauses the guidance: no artwork is visited, no off-route warning or wrong floor shows, and the
     * navigator leaves the leg where it is (`ASK_APP`). The dot moves and the route stays. A detour
     * keeps its leg.
     */
    fun pause() {
        if (mutablePaused.value) return
        mutablePaused.value = true
        FieldLog.log("journey paused")
        closeOffRouteWarning()
        isOffRouteEpisode = false
        wrongFloorSince = null
        navigator.deviationPolicy = JourneyDeviationPolicy.ASK_APP
    }

    /**
     * Ends [pause]. The leg is drawn again from the visitor; a personalised journey is ordered again
     * from the visitor first. A detour keeps its leg.
     */
    suspend fun resumeFromPause() {
        if (!mutablePaused.value) return
        mutablePaused.value = false
        FieldLog.log("journey resumed after a pause")
        navigator.deviationPolicy = deviationPolicy
        if (!isNavigating || navigator.detourStop != null) return
        serially {
            rerouting {
                orderRemaining()
                navigator.resumeJourney()
            }
            save()
        }
    }

    /** "Dismiss" on the off-route warning. The leg is not drawn again. The warning opens again on the next episode. */
    fun dismissOffRoute() {
        closeOffRouteWarning()
    }

    /** Saves the journey, as iOS `VisitView` does on every change of `navigator.journey`. */
    fun save() {
        if (!hasEnded) persist(journey)
    }

    /** Stops driving the map. The journey stays stored. */
    fun close() {
        cancelJobs()
        navigator.end()
    }

    /** The visit is over (Journey Complete closed): the stored visit and its title are removed. */
    fun end() {
        hasEnded = true
        cancelJobs()
        navigator.end()
        persist(null)
    }

    private fun cancelJobs() {
        closeOffRouteWarning()
        eventsJob?.cancel()
        doneJobs.forEach { it.cancel() }
        doneJobs.clear()
    }

    companion object {
        /**
         * Whether an artwork entered out of order becomes the current stop and waits for the visit
         * rules, or is visited at the entry. iOS `VisitController.arrivesOutOfOrder(settings:)`.
         */
        fun arrivesOutOfOrder(settings: GadSettings): Boolean = settings.advancesOnArtworkExit && !settings.visitsOutOfOrderAtEntry

        /** The order strategy for [settings]. Pure; covered by tests. */
        internal fun orderStrategy(settings: GadSettings): JourneyOrderStrategy =
            if (settings.ordersGalleriesByWalkingTime) JourneyOrderStrategy.FLOOR_THEN_GALLERY_ROUTED else JourneyOrderStrategy.FLOOR_THEN_GALLERY

        /**
         * Guidance on a leg to an artwork. The infinite dwell turns the SDK's arrival by distance off;
         * the artwork geofence is the only arrival. Off the leg beyond [GadSettings.offRouteMeters]. The
         * walked part of the route moves only within [GadSettings.progressCorridorMeters] of the leg.
         * Pure; covered by tests.
         */
        internal fun artworkGuidanceRules(settings: GadSettings): RouteFollowRules = RouteFollowRules(
            arrivalDwellSeconds = Double.POSITIVE_INFINITY,
            offRouteMeters = settings.offRouteMeters,
        ).copy(progressCorridorMeters = settings.progressCorridorMeters)

        /**
         * Guidance on a leg to an amenity detour: arrival within 2.5 × the fix accuracy, within
         * [GadSettings.amenityArrivalMinimumMeters] and [GadSettings.amenityArrivalMaximumMeters], for
         * [GadSettings.amenityArrivalDwell]. The SDK default (at least 3 m, 2 s) missed visitors who
         * walked past a stop with relay fixes. Pure; covered by tests.
         */
        internal fun detourGuidanceRules(settings: GadSettings): RouteFollowRules = RouteFollowRules(
            arrival = RouteFollowRules.ArrivalRadius.FromAccuracy(
                multiplier = JourneyConstants.ARRIVAL_ACCURACY_MULTIPLIER,
                minimumMeters = settings.amenityArrivalMinimumMeters,
                maximumMeters = settings.amenityArrivalMaximumMeters,
            ),
            arrivalDwellSeconds = settings.amenityArrivalDwell.inWholeMilliseconds / 1000.0,
            offRouteMeters = settings.offRouteMeters,
        ).copy(progressCorridorMeters = settings.progressCorridorMeters)

        /**
         * What one guidance update off or on the leg does. An open episode, of the leg or of the whole
         * journey, shows no second warning; the leg's episode ends within two thirds of [threshold], so
         * a visitor on the boundary gets one warning. Pure; covered by tests.
         */
        internal fun legDeviation(isOffRoute: Boolean, meters: Double, threshold: Double, isEpisode: Boolean, isLegEpisode: Boolean): LegDeviation {
            if (isOffRoute) return if (isEpisode) LegDeviation.NONE else LegDeviation.WARNS
            return if (isLegEpisode && meters <= threshold * 2 / 3) LegDeviation.ENDS else LegDeviation.NONE
        }

        /** The closer-artwork check runs at most this often. */
        private const val CLOSER_ARTWORK_INTERVAL_MILLIS = 5_000L

        /**
         * Whether a route still to walk takes the floor at [level]. No route still to walk counts as
         * on the route. Pure; covered by tests.
         */
        internal fun isOnRouteFloor(level: Double, legs: List<RouteGeometry.Leg>): Boolean {
            val ahead = legs.filter { !it.isCompleted }
            return ahead.isEmpty() || ahead.any { VenueLevel.same(it.floor.level, level) }
        }

        /**
         * The shapes of the artwork geofences, by `remote_id`. One artwork can have several
         * geofences: on 2026-10-15 each DCT test artwork shares its `remote_id` with the Guggenheim
         * artwork. Pure; covered by tests.
         */
        internal fun artworkAreas(geofences: List<VenueGeofence>): Map<String, List<ArtworkArea>> =
            geofences
                .filter { it.geofenceType == "artwork" && !it.remoteId.isNullOrEmpty() }
                .groupBy({ it.remoteId.orEmpty() }, { ArtworkArea(it.center, it.radius, it.polygon, it.id) })
    }
}
