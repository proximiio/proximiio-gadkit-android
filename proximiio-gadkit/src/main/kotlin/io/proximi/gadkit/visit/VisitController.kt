package io.proximi.gadkit.visit

import io.proximi.gadkit.map.RouteAccessibility
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
import io.proximi.map.live.JourneyNavigator
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
 *   therefore also checked against the artwork geofences ([checkArtworkGeofences]).
 * - The journey moves to the next artwork 2 s after the arrival.
 * - Artwork legs have no arrival by distance ([artworkGuidanceRules]). An artwork the visitor walks
 *   past without entering its geofence stays unvisited until it is skipped.
 * - Out of order: a visitor inside the geofence of another open artwork for
 *   [OUT_OF_ORDER_DWELL_MILLIS] (0 while testing) marks it visited (PM review 2026-10-12).
 * - After an out-of-order visit, the stops still ahead are ordered again from the visitor.
 * - An amenity has no geofence. A detour is reached within 5–10 m for 5 s ([detourGuidanceRules]).
 * - After a reached detour, the journey continues only after "Resume your Journey" ([rules]).
 *
 * A new personalised journey is ordered floor by floor and gallery by gallery
 * (`JourneyOrderStrategy.FLOOR_THEN_GALLERY`): the stop nearest the visitor first, then the rest of
 * its gallery, then the rest of its floor. Without a fix the first stop stays first, and the first
 * fix within 60 s orders the journey again while no stop is reached. A restored journey keeps its
 * order. After a detour the stops still ahead are ordered again from the visitor. A curated tour
 * keeps the curators' order ([keepsOrder]).
 *
 * Leaving the route does not re-route the leg (`JourneyDeviationPolicy.ASK_APP`). The visitor gets a
 * warning ([isOffRoute]) after the navigator reports `FarFromRoute` or `OffRouteTooLong`. "Show the
 * route from here" re-routes from the current position.
 *
 * Skip, undo and the EC04 pick edit the running navigator (`skip`, `reopen`, `activate`), so its
 * route cache, event streams and deviation state are kept. Main thread only.
 */
internal class VisitController(
    val session: ProximiioMapSession,
    journey: CoreJourney,
    private val store: VisitStore,
    private val scope: CoroutineScope,
    /** The visitor's accessibility needs for every leg ([io.proximi.gadkit.map.RouteAccessibility]). */
    accessibility: RouteAccessibility = RouteAccessibility.NONE,
    /** The drawable resource name of the app's glyph for an amenity, for the detour marker. */
    private val amenityIconName: (VenuePlace) -> String? = { null },
    autoAdvance: Boolean = false,
    /**
     * A curated tour keeps the curators' order: no ordering at the start, on the first fix, after
     * a detour or after an artwork visited out of order ([JourneyKind.keepsOrder]).
     */
    private val keepsOrder: Boolean = false,
) {
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

    val navigator = JourneyNavigator(session, journey.toMap(), rules, artworkGuidanceRules)

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
        session.routeSnapping = routeSnapping
    }

    /** `remote_id` of each artwork POI, by POI id. Set once the venue's features are loaded. */
    var artworkRemoteIds: Map<String, String> = emptyMap()

    /** Artwork geofence shapes, by `remote_id`. Set once the venue's geofences are loaded. */
    var artworkAreas: Map<String, ArtworkArea> = emptyMap()

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

    private var eventsJob: Job? = null
    private val doneJobs = mutableListOf<Job>()

    val journey: CoreJourney get() = navigator.journey.value.toCore()

    suspend fun start() {
        navigator.deviationPolicy = JourneyDeviationPolicy.ASK_APP
        navigator.setRouteOptions(routeOptions)
        var isOrderedWithoutFix = false
        if (!keepsOrder && navigator.journey.value.stops.all { it.state == JourneyStop.State.PENDING }) {
            waitForFix(FIX_WAIT_MILLIS)
            isOrderedWithoutFix = session.position.value == null
            proposedOrder()?.takeIf { it.isImprovement }?.let { apply(it) }
        }
        navigator.start()
        eventsJob = scope.launch { navigator.events.collect(::handle) }
        if (isOrderedWithoutFix) doneJobs += scope.launch { orderOnFirstFix() }
    }

    private suspend fun waitForFix(limitMillis: Long) {
        withTimeoutOrNull(limitMillis) { session.position.first { it != null } }
    }

    /** Orders the journey from the first fix while no stop is reached, done or skipped. */
    private suspend fun orderOnFirstFix() {
        waitForFix(LATE_ORDER_WINDOW_MILLIS)
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
        when (event) {
            is JourneyEvent.StopReached -> if (event.stop.kind != JourneyStop.Kind.DETOUR) scheduleDone(event.stop.id)
            is JourneyEvent.DetourEnded -> if (event.completed) doneJobs += scope.launch { orderRemaining() }
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
        if (navigator.detourStop != null) return
        val id = ArtworkArrival.activeStopId(remoteId, journey, artworkRemoteIds) ?: return
        FieldLog.log("artwork geofence arrival: $id remote ${remoteId ?: "-"}")
        scheduleDone(id)
    }

    /**
     * Checks the fix against the geofences of the open artworks on the visitor's floor. Inside the
     * active artwork's geofence: the arrival, which also covers a leg that starts inside it, where
     * the SDK sends no enter event. Inside another open artwork's geofence for
     * [OUT_OF_ORDER_DWELL_MILLIS]: that artwork is visited out of order. During a detour to an
     * amenity no artwork counts (PM review 2026-10-12).
     */
    fun checkArtworkGeofences() {
        if (navigator.detourStop != null) {
            outOfOrderDwell.update(emptySet(), System.currentTimeMillis(), OUT_OF_ORDER_DWELL_MILLIS)
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
            val area = artworkAreas[remoteId] ?: continue
            val stopLevel = stop.floor?.level
            if (!VenueLevel.matches(stopLevel, level)) continue
            if (!area.contains(point)) continue
            if (stop.id == activeId) artworkGeofenceEntered(remoteId) else inside += stop.id
        }
        for (id in outOfOrderDwell.update(inside, System.currentTimeMillis(), OUT_OF_ORDER_DWELL_MILLIS)) markOutOfOrder(id)
    }

    /** Marks a pending artwork visited out of order and orders the stops still ahead again from the visitor. */
    private fun markOutOfOrder(stopId: String) {
        if (!markingOutOfOrder.add(stopId)) return
        doneJobs += scope.launch {
            try {
                if (navigator.journey.value.stops.firstOrNull { it.id == stopId }?.state != JourneyStop.State.PENDING) return@launch
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

    /** Marks [stopId] visited after 2 s if it is still the active stop. The leg to the next artwork is drawn. */
    private fun scheduleDone(stopId: String) {
        if (!scheduledDone.add(stopId)) return
        doneJobs += scope.launch {
            try {
                delay(JourneyConstants.AUTO_ADVANCE_DELAY_MILLIS)
            } finally {
                scheduledDone.remove(stopId)
            }
            if (navigator.activeStop?.id != stopId) return@launch
            val changed = navigator.markDone(stopId)
            save()
            if (changed) doneStop(stopId)?.let { onArtworkDone?.invoke(it, true) }
        }
    }

    private fun doneStop(stopId: String): JourneyStop? = navigator.journey.value.stops.firstOrNull { it.id == stopId }

    suspend fun skip(stopId: String) {
        if (!navigator.skip(stopId)) return
        mutableLastSkipped.value = stopId
        save()
    }

    suspend fun undoSkip() {
        val id = mutableLastSkipped.value ?: return
        mutableLastSkipped.value = null
        navigator.reopen(id)
        save()
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
        val hadDetour = navigator.detourStop != null
        navigator.resumeJourney()
        if (hadDetour) orderRemaining()
        save()
    }

    /** Closes the off-route warning without re-routing. It opens again on the next episode. */
    fun dismissOffRoute() {
        mutableOffRoute.value = false
    }

    /** Saves the journey, as iOS `VisitView` does on every change of `navigator.journey`. */
    fun save() {
        if (!hasEnded) store.save(journey)
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
        store.save(null)
        store.saveMeta(null)
    }

    private fun cancelJobs() {
        eventsJob?.cancel()
        doneJobs.forEach { it.cancel() }
        doneJobs.clear()
    }

    companion object {
        /**
         * The dot is drawn on the route when the position is within 0.5 m of it. Display only:
         * guidance and arrival use the real position (PM review 2026-10-12).
         */
        val routeSnapping = RouteSnapRules(maximumOffsetMeters = 0.5)

        /**
         * Time inside the geofence of an artwork that is not the active stop before it counts as
         * visited. 0 while testing (PM review 2026-10-12); a longer time keeps a visitor who
         * crosses the geofence on the way elsewhere from marking it.
         */
        const val OUT_OF_ORDER_DWELL_MILLIS = 0L

        /** How long [start] waits for a fix before it orders the journey without one. */
        const val FIX_WAIT_MILLIS = 5_000L

        /** How long a journey ordered without a fix waits for the first fix to order it again. */
        const val LATE_ORDER_WINDOW_MILLIS = 60_000L

        /**
         * Guidance on a leg to an artwork. The infinite dwell turns the SDK's arrival by distance
         * off; the artwork geofence is the only arrival.
         */
        val artworkGuidanceRules = RouteFollowRules(arrivalDwellSeconds = Double.POSITIVE_INFINITY)

        /**
         * Guidance on a leg to an amenity detour: arrival within 2.5 × the fix accuracy, at least
         * 5 m and at most 10 m, for 5 s. The SDK default (at least 3 m, 2 s) missed visitors who
         * walked past a stop with relay fixes.
         */
        val detourGuidanceRules = RouteFollowRules(
            arrival = RouteFollowRules.ArrivalRadius.FromAccuracy(
                multiplier = JourneyConstants.ARRIVAL_ACCURACY_MULTIPLIER,
                minimumMeters = JourneyConstants.ARRIVAL_MIN_METERS,
                maximumMeters = JourneyConstants.ARRIVAL_MAX_METERS,
            ),
            arrivalDwellSeconds = JourneyConstants.ARRIVAL_DWELL_SECONDS,
        )
    }
}
