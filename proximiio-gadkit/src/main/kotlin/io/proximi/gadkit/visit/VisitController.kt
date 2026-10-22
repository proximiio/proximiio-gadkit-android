package io.proximi.gadkit.visit

import io.proximi.gadkit.GadSettings
import io.proximi.gadkit.JourneyUpdate
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.map.VenueGeofence
import io.proximi.gadkit.map.VenueLevel
import io.proximi.gadkit.FieldLog
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
import io.proximi.map.core.RouteSnapRules
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
import io.proximi.gadkit.visit.Journey as CoreJourney

/**
 * Owns the `JourneyNavigator` of a visit, persists the journey, and implements skip and undo, as
 * iOS `VisitController`.
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
 * - The journey moves to the next artwork 2 s after the arrival.
 * - Artwork legs have no arrival by distance ([artworkGuidanceRules]). An artwork the visitor walks
 *   past without entering its geofence stays unvisited until it is skipped.
 * - Out of order: a visitor inside the geofence of another open artwork for
 *   [GadSettings.outOfOrderDwell] (0 while testing) marks it visited (PM review 2026-10-12).
 * - After an out-of-order visit, the stops still ahead are ordered again from the visitor.
 * - An amenity has no geofence. A detour is reached within 5–10 m for 5 s ([detourGuidanceRules],
 *   [GadSettings]).
 * - After a reached detour, the journey continues only after "Resume your Journey" ([rules]).
 *
 * A new personalised journey is ordered floor by floor and gallery by gallery
 * (`JourneyOrderStrategy.FLOOR_THEN_GALLERY`): the stop nearest the visitor first, then the rest of
 * its gallery, then the rest of its floor. Without a fix the first stop stays first, and the first
 * fix within 60 s orders the journey again while no stop is reached. A restored journey keeps its
 * order. After a detour the stops still ahead are ordered again from the visitor. A curated tour
 * keeps the curators' order ([keepsOrder]).
 *
 * With prompts ([io.proximi.gadkit.ProximiMapView.Option.PROMPTS]), leaving the route does not
 * re-route the leg (`JourneyDeviationPolicy.ASK_APP`): the visitor gets a warning ([isOffRoute])
 * after the navigator reports `FarFromRoute` or `OffRouteTooLong`, and "Show the route from here"
 * re-routes from the current position. Without prompts the navigator re-routes from the visitor
 * (`AUTOMATIC`).
 *
 * Without [managesProgress] ([io.proximi.gadkit.ProximiMapView.Option.PROGRESS]) the host app moves
 * the journey on: no arrival by geofence, no ordering, and [apply] takes the host app's changes.
 *
 * Skip, undo and the EC04 pick edit the running navigator (`skip`, `reopen`, `activate`), so its
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
     * [io.proximi.gadkit.ProximiMapView.Option.PROGRESS]: artwork geofences mark stops visited, the
     * journey is ordered, and a stop whose POI is deleted is skipped. Off, only [apply] changes the
     * stops.
     */
    private val managesProgress: Boolean = true,
    /**
     * [io.proximi.gadkit.ProximiMapView.Option.PROMPTS]: the leg stays where the visitor left it
     * until "Show the route from here" (`ASK_APP`). Off, the navigator routes again from the visitor.
     */
    private val deviationPolicy: JourneyDeviationPolicy = JourneyDeviationPolicy.ASK_APP,
    /** Times and distances: arrival, out-of-order dwell, off route, route snap, journey order. */
    private val settings: GadSettings = GadSettings.STANDARD,
) {
    private val keepsOrder = keepsOrder || !managesProgress

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
    private val galleryRouting = GalleryFirstRouting(ProximiioJourneyRouting(checkNotNull(session.sdk)))

    val navigator = JourneyNavigator(session, journey.toMap(), rules, artworkGuidanceRules, routing = galleryRouting)

    private val mutableGalleryAhead = MutableStateFlow<GalleryDestination?>(null)

    /** The gallery the leg to the active artwork ends at, or `null` when it ends at the artwork. */
    val galleryAhead: StateFlow<GalleryDestination?> = mutableGalleryAhead.asStateFlow()

    /** The journey changes that run one after another ([serially]). */
    private val journeyEdits = Mutex()

    /**
     * Guidance on a leg to an amenity detour: arrival within 2.5 × the fix accuracy, within
     * [GadSettings.amenityArrivalMinimumMeters] and [GadSettings.amenityArrivalMaximumMeters], for
     * [GadSettings.amenityArrivalDwell]. The SDK default (at least 3 m, 2 s) missed visitors who
     * walked past a stop with relay fixes.
     */
    private val detourGuidanceRules = RouteFollowRules(
        arrival = RouteFollowRules.ArrivalRadius.FromAccuracy(
            multiplier = JourneyConstants.ARRIVAL_ACCURACY_MULTIPLIER,
            minimumMeters = settings.amenityArrivalMinimumMeters,
            maximumMeters = settings.amenityArrivalMaximumMeters,
        ),
        arrivalDwellSeconds = settings.amenityArrivalDwell.inWholeMilliseconds / 1000.0,
    )

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
        // "You've left your route": this far from the route, or off it this long.
        navigator.deviationRules = navigator.deviationRules.copy(
            farFromRouteMeters = settings.offRouteMeters,
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

    /**
     * Called when an artwork stop is done: `true` for the active stop (its geofence, 2 s after the
     * arrival), `false` for an artwork visited out of order. The stop is read after `markDone`.
     */
    var onArtworkDone: ((JourneyStop, isActiveStop: Boolean) -> Unit)? = null

    /** Stops whose move to the next artwork is scheduled. */
    private val scheduledDone = mutableSetOf<String>()

    /** Time inside the geofence of each open artwork that is not the active stop. */
    private val outOfOrderDwell = GeofenceDwell()

    /** Out-of-order artworks being marked visited. */
    private val markingOutOfOrder = mutableSetOf<String>()

    private val mutableLastSkipped = MutableStateFlow<String?>(null)

    /** The last skipped stop, for "Undo skip". Cleared by the next skip or by undo. */
    val lastSkippedStopId: StateFlow<String?> = mutableLastSkipped.asStateFlow()

    private val mutableOffRoute = MutableStateFlow(false)

    /**
     * `true` after `FarFromRoute` or `OffRouteTooLong`, until the visitor returns to the route or
     * answers the warning.
     */
    val isOffRoute: StateFlow<Boolean> = mutableOffRoute.asStateFlow()

    /** `true` after [end]: the visit is over and nothing is saved any more. */
    private var hasEnded = false

    private var isStarted = false

    /** `true` once `navigator.start()` has returned. */
    private var isNavigating = false

    /** The host app's latest journey while [apply] runs. */
    private var pendingTarget: CoreJourney? = null
    private var isApplying = false

    /** Stops whose POI is no longer a place, reported once ([stopsWithoutPlace]). */
    private val reportedWithoutPlace = mutableSetOf<String>()

    private var eventsJob: Job? = null
    private val doneJobs = mutableListOf<Job>()

    val journey: CoreJourney get() = navigator.journey.value.toCore()

    /** Starts the journey once. A recreated screen calls it again. */
    suspend fun start() {
        if (isStarted) return
        isStarted = true
        navigator.deviationPolicy = deviationPolicy
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
        val proposal = navigator.proposeOrder(JourneyOrderOrigin.VISITOR, strategy = JourneyOrderStrategy.FLOOR_THEN_GALLERY) ?: return
        if (proposal.isImprovement) apply(proposal)
    }

    /** The floor-then-gallery order from the visitor's fix, or from the first stop without a fix. */
    private suspend fun proposedOrder(): JourneyOrderProposal? =
        navigator.proposeOrder(JourneyOrderOrigin.VISITOR, strategy = JourneyOrderStrategy.FLOOR_THEN_GALLERY)
            ?: navigator.proposeOrder(JourneyOrderOrigin.ACTIVE_STOP, strategy = JourneyOrderStrategy.FLOOR_THEN_GALLERY)

    /** Orders the stops still ahead from the visitor, floor by floor and gallery by gallery. */
    private suspend fun orderRemaining() {
        if (keepsOrder) return
        val proposal = navigator.proposeOrder(JourneyOrderOrigin.VISITOR, strategy = JourneyOrderStrategy.FLOOR_THEN_GALLERY) ?: return
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
            is JourneyEvent.StopReached -> if (event.stop.kind != JourneyStop.Kind.DETOUR) scheduleDone(event.stop.id)
            is JourneyEvent.DetourEnded -> if (event.completed) doneJobs += scope.launch { serially { orderRemaining() } }
            is JourneyEvent.FarFromRoute, is JourneyEvent.OffRouteTooLong -> mutableOffRoute.value = true
            JourneyEvent.ReturnedToRoute -> mutableOffRoute.value = false
            else -> Unit
        }
    }

    /**
     * The visitor entered an artwork geofence. The active artwork moves on after 2 s. The
     * geofence of any other artwork is ignored, and every artwork geofence is ignored during a
     * detour.
     */
    fun artworkGeofenceEntered(remoteId: String?) {
        if (!managesProgress || navigator.detourStop != null) return
        val id = ArtworkArrival.activeStopId(remoteId, journey, artworkRemoteIds) ?: return
        FieldLog.log("artwork geofence arrival: $id remote ${remoteId ?: "-"}")
        scheduleDone(id)
    }

    /**
     * Checks the fix against the geofences of the open artworks on the visitor's floor, and against
     * [enteredGeofenceIds]. Inside the active artwork's geofence: the arrival, which also covers a
     * leg that starts inside it, where the SDK sends no enter event. Inside another open artwork's
     * geofence for [GadSettings.outOfOrderDwell]: that artwork is visited out of order. During a
     * detour to an amenity no artwork counts (PM review 2026-10-12).
     */
    fun checkArtworkGeofences() {
        // Before the start the journey is still being ordered.
        if (!managesProgress || !isNavigating) return
        if (navigator.detourStop != null) {
            outOfOrderDwell.update(emptySet(), System.currentTimeMillis(), outOfOrderDwellMillis)
            return
        }
        val position = session.position.value ?: return
        val point = Coordinate(position.coordinate.latitude, position.coordinate.longitude)
        val level = position.floor?.level
        val activeId = navigator.activeStop?.id
        val inside = mutableSetOf<String>()
        for (stop in navigator.journey.value.stops) {
            if (stop.kind == JourneyStop.Kind.DETOUR) continue
            if (stop.state != JourneyStop.State.PENDING && stop.state != JourneyStop.State.ACTIVE && stop.state != JourneyStop.State.REACHED) continue
            val remoteId = artworkRemoteIds[stop.poiId ?: stop.id] ?: continue
            val areas = artworkAreas[remoteId] ?: continue
            val stopLevel = stop.floor?.level
            if (!VenueLevel.matches(stopLevel, level)) continue
            if (areas.none { it.id in enteredGeofenceIds || it.contains(point) }) continue
            if (stop.id == activeId) artworkGeofenceEntered(remoteId) else inside += stop.id
        }
        for (id in outOfOrderDwell.update(inside, System.currentTimeMillis(), outOfOrderDwellMillis)) markOutOfOrder(id)
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
            if (!isNavigating || navigator.detourStop != null || navigator.hasArrived) return
            val stop = navigator.activeStop ?: return
            val gallery = galleryRouting.gallery(stop.coordinate) ?: return
            val position = session.position.value ?: return
            if (!gallery.gallery.contains(Coordinate(position.coordinate.latitude, position.coordinate.longitude), position.floor?.level)) return
            galleryRouting.markReached(stop.coordinate)
            FieldLog.log("gallery first: in ${gallery.gallery.name} · leg to ${stop.title}")
            doneJobs += scope.launch {
                serially {
                    mutableOffRoute.value = false
                    navigator.resumeJourney()
                }
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

    private fun refreshGalleryAhead() {
        mutableGalleryAhead.value = if (navigator.detourStop == null) navigator.activeStop?.let { galleryRouting.gallery(it.coordinate) } else null
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
                    if (navigator.markDone(stopId)) doneStop(stopId)?.let { onArtworkDone?.invoke(it, false) }
                    orderRemaining()
                    // The navigator draws a new leg only when the active stop changes. Without a change
                    // the old leg, from where the visitor was, would stay (PM "Route drawing when going
                    // through artworks in wrong order").
                    if (activeStopId != null && navigator.activeStop?.id == activeStopId) {
                        FieldLog.log("leg to $activeStopId drawn again from the visitor")
                        mutableOffRoute.value = false
                        navigator.resumeJourney()
                    }
                    save()
                } finally {
                    markingOutOfOrder.remove(stopId)
                }
            }
        }
    }

    /** Marks [stopId] visited after 2 s if it is still the active stop. The leg to the next artwork is drawn. */
    private fun scheduleDone(stopId: String) {
        if (!scheduledDone.add(stopId)) return
        doneJobs += scope.launch {
            try {
                delay(settings.artworkAdvanceDelay)
            } catch (cancelled: CancellationException) {
                scheduledDone.remove(stopId)
                throw cancelled
            }
            serially {
                scheduledDone.remove(stopId)
                if (navigator.activeStop?.id != stopId) return@serially
                val changed = navigator.markDone(stopId)
                save()
                if (changed) doneStop(stopId)?.let { onArtworkDone?.invoke(it, true) }
            }
        }
    }

    private fun doneStop(stopId: String): JourneyStop? = navigator.journey.value.stops.firstOrNull { it.id == stopId }

    suspend fun skip(stopId: String) {
        if (!navigator.skip(stopId)) return
        mutableLastSkipped.value = stopId
        save()
    }

    /**
     * The stops whose POI is no longer a place after a change of the venue data, each once. With
     * [managesProgress] they are skipped: the library does not use the navigator's `remove` and
     * `replace` of map 6.0.0-beta.22 yet, as the iOS package 1.7.0. When the journey screen of `ProximiMap` closes, the stored journey drops
     * these stops and takes the changed places ([VisitPlan.reconciled]). Without [managesProgress]
     * the stops stay; the host app decides.
     */
    suspend fun stopsWithoutPlace(places: List<VenuePlace>): List<io.proximi.gadkit.visit.JourneyStop> {
        val stops = VisitPlan.stopsWithoutPlace(journey, places).filter { it.state != StopState.SKIPPED && it.id !in reportedWithoutPlace }
        if (stops.isEmpty()) return emptyList()
        reportedWithoutPlace += stops.map { it.id }
        if (!managesProgress) return stops
        for (stop in stops) {
            FieldLog.log("journey: ${stop.title} is no longer a place · skipped")
            navigator.skip(stop.id)
        }
        save()
        return stops
    }

    suspend fun undoSkip() {
        val id = mutableLastSkipped.value ?: return
        mutableLastSkipped.value = null
        navigator.reopen(id)
        save()
    }

    /**
     * Takes the host app's journey on the open screen (`ProximiMapView`): stops added, visited,
     * skipped, reopened and moved. The navigator, its route cache and the camera stay. [target] holds
     * every stop of the navigator ([io.proximi.gadkit.HostedJourney.canReplace]). Calls run one after
     * another; a call during another keeps only the latest journey, applied after it. Before the
     * navigator has started, the latest journey waits for the start.
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

    /** Adds the new stops, reopens, sets the order in one transition, then closes. Each step calls the navigator only for a stop that changes. */
    private suspend fun performApply(target: CoreJourney) {
        val existing = navigator.journey.value.stops.mapTo(HashSet()) { it.id }
        var added = 0
        for (stop in target.stops) {
            if (stop.id in existing) continue
            if (navigator.add(stop.copy(state = StopState.PENDING).toMap())) added++
        }
        val changes = JourneyUpdate.stateChanges(journey, target)
        for (change in changes) if (change.kind == JourneyUpdate.StateChange.Kind.REOPEN) navigator.reopen(change.stopId)
        val closing = changes.filter { it.kind != JourneyUpdate.StateChange.Kind.REOPEN }
        val current = navigator.reorderableStops.map { it.id }
        val active = navigator.activeStop?.id
        val ordered = JourneyUpdate.order(
            reorderable = current,
            activeStopId = active,
            pending = target.stops.filter { it.state == StopState.PENDING }.map { it.id },
            closing = closing.mapTo(HashSet()) { it.stopId },
        )
        if (ordered != null) {
            navigator.apply(
                JourneyOrderProposal(
                    order = ordered, currentOrder = current, pinnedStopIds = emptyList(),
                    currentMeters = 0.0, proposedMeters = 0.0, liveStopId = active,
                ),
            )
        }
        for (change in closing) {
            when (change.kind) {
                JourneyUpdate.StateChange.Kind.MARK_DONE -> navigator.markDone(change.stopId)
                JourneyUpdate.StateChange.Kind.SKIP -> navigator.skip(change.stopId)
                JourneyUpdate.StateChange.Kind.REOPEN -> Unit
            }
        }
        // "Undo skip" belongs to a skip the host app has since changed.
        val skipped = mutableLastSkipped.value
        if (skipped != null && doneStop(skipped)?.state != JourneyStop.State.SKIPPED) mutableLastSkipped.value = null
        FieldLog.log("journey applied · $added added · ${changes.size} state changes · order ${if (ordered == null) "kept" else "set"}")
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
     */
    suspend fun detour(place: VenuePlace) {
        navigator.guidanceRules = detourGuidanceRules
        navigator.detour(VisitPlan.stop(place).toMap().copy(iconName = amenityIconName(place)))
        navigator.guidanceRules = artworkGuidanceRules
    }

    /**
     * "Resume your Journey" after a detour and "Show the route from here" after leaving the route.
     * A detour reached is recorded as visited; one not reached is dropped. The leg is drawn from here.
     */
    suspend fun resumeJourney() {
        mutableOffRoute.value = false
        serially {
            val hadDetour = navigator.detourStop != null
            navigator.resumeJourney()
            if (hadDetour) orderRemaining()
            save()
        }
    }

    /** Closes the off-route warning without re-routing. It opens again on the next episode. */
    fun dismissOffRoute() {
        mutableOffRoute.value = false
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
        eventsJob?.cancel()
        doneJobs.forEach { it.cancel() }
        doneJobs.clear()
    }

    companion object {
        /**
         * Guidance on a leg to an artwork. The infinite dwell turns the SDK's arrival by distance
         * off; the artwork geofence is the only arrival.
         */
        val artworkGuidanceRules = RouteFollowRules(arrivalDwellSeconds = Double.POSITIVE_INFINITY)

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
