package io.proximi.gadkit

import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.visit.StopKind
import io.proximi.gadkit.visit.StopState
import io.proximi.gadkit.visit.VisitPlan
import io.proximi.map.core.Journey as MapJourney
import io.proximi.map.core.JourneyStop as MapJourneyStop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A journey and its progress, for [ProximiMapView], as iOS `ProximiJourney`. The host app owns it:
 * it creates it, stores it, moves it on and passes it to the view. The view reports the stop order
 * and the stop states through `onJourneyChange` only with [ProximiMapView.Option.PROGRESS].
 *
 * @property stops the stops in visit order. The route leads to the first pending stop. With
 *   [ProximiMapView.Option.PROGRESS] the view orders a personalised journey floor by floor and
 *   gallery by gallery from the visitor and reports the new order.
 */
public data class ProximiJourney(
    public val kind: JourneyKind = JourneyKind.PERSONALISED,
    public val stops: List<Stop>,
) {
    /**
     * @property id the id the host app gave: a collection id (`remote_id`) or a Proximi.io POI id.
     */
    public data class Stop(public val id: String, public val state: State = State.PENDING)

    public enum class State(public val rawValue: String) {
        /** Not visited yet. The stop the route leads to is pending too. */
        PENDING("pending"),

        /**
         * Visited. With [ProximiMapView.Option.PROGRESS] the view sets it when the visitor reaches
         * the artwork or stays at it out of order.
         */
        VISITED("visited"),

        /**
         * Skipped. With [ProximiMapView.Option.PROGRESS] the view sets it when the visitor skips the
         * artwork in the list or its POI is deleted while the view is open.
         */
        SKIPPED("skipped"),
    }

    /** The journey as JSON, the format of iOS `ProximiJourney: Codable`: `{"kind": …, "stops": [{"id": …, "state": …}]}`. */
    public fun toJson(): String = JsonObject(
        mapOf(
            "kind" to JsonPrimitive(kind.rawValue),
            "stops" to JsonArray(stops.map { JsonObject(mapOf("id" to JsonPrimitive(it.id), "state" to JsonPrimitive(it.state.rawValue))) }),
        ),
    ).toString()

    public companion object {
        /**
         * A journey whose stops are all pending.
         *
         * @param artworks collection ids (`remote_id`, for example "GAD.2010.00033") or Proximi.io POI
         *   ids, in visit order.
         */
        public fun ofArtworks(artworks: List<String>, kind: JourneyKind = JourneyKind.PERSONALISED): ProximiJourney =
            ProximiJourney(kind, artworks.map { Stop(it) })

        /**
         * The stops of a map library journey, for a host app that built a `Journey` for its own
         * `JourneyNavigator`. Each stop keeps its id (`JourneyStop(poi)` uses the POI id). A done stop
         * is visited, a skipped stop is skipped, and a pending, active or reached stop is pending. A
         * detour stop is left out.
         */
        public fun ofJourney(journey: MapJourney, kind: JourneyKind = JourneyKind.PERSONALISED): ProximiJourney =
            ProximiJourney(
                kind,
                journey.stops.filter { it.kind != MapJourneyStop.Kind.DETOUR }.map { stop ->
                    val state = when (stop.state) {
                        MapJourneyStop.State.DONE -> State.VISITED
                        MapJourneyStop.State.SKIPPED -> State.SKIPPED
                        MapJourneyStop.State.PENDING, MapJourneyStop.State.ACTIVE, MapJourneyStop.State.REACHED -> State.PENDING
                    }
                    Stop(stop.id, state)
                },
            )

        /** The journey of [toJson], or `null` for unreadable JSON. An unknown kind is personalised. */
        public fun fromJson(text: String): ProximiJourney? = runCatching {
            val fields = Json.parseToJsonElement(text).jsonObject
            ProximiJourney(
                kind = JourneyKind.of(fields["kind"]?.jsonPrimitive?.content),
                stops = fields.getValue("stops").jsonArray.map { element ->
                    val stop = element.jsonObject
                    val state = stop.getValue("state").jsonPrimitive.content
                    Stop(stop.getValue("id").jsonPrimitive.content, State.entries.first { it.rawValue == state })
                },
            )
        }.getOrNull()
    }
}

/** A [ProximiJourney] matched to the venue's places, for the journey screen. */
internal data class HostedJourney(
    /** The navigator's journey: one stop per matched host stop. */
    val journey: Journey,
    /** The host app's stop id of each POI id. */
    val hostIds: Map<String, String>,
    /**
     * The host app's stops that match no POI, and a second stop of the same POI. The map does not
     * show them; the journey written back keeps them after the others.
     */
    val unmatched: List<ProximiJourney.Stop>,
) {
    /**
     * Whether the open screen, which shows [shown], can take this journey in place: the same kind, a
     * stop on the map in both, and every stop of [shown] still in this one. The library does not use
     * the navigator's `remove` of map 6.0.0-beta.22 yet, and a screen without stops has no started
     * navigator to add them to. Pure; covered by tests.
     */
    fun canReplace(shown: HostedJourney, kind: JourneyKind, shownKind: JourneyKind): Boolean =
        kind == shownKind && shown.journey.stops.isNotEmpty() && journey.stops.isNotEmpty() &&
            journey.stops.map { it.id }.containsAll(shown.journey.stops.map { it.id })

    /**
     * The host app's journey with the order and states of [journey]. Detours are left out. A reached
     * stop stays pending until it is done. Pure; covered by tests.
     */
    fun hostJourney(journey: Journey, kind: JourneyKind): ProximiJourney {
        val stops = journey.stops.mapNotNull { stop ->
            if (stop.kind == StopKind.DETOUR) return@mapNotNull null
            val id = hostIds[stop.poiId ?: stop.id] ?: return@mapNotNull null
            val state = when (stop.state) {
                StopState.DONE -> ProximiJourney.State.VISITED
                StopState.SKIPPED -> ProximiJourney.State.SKIPPED
                else -> ProximiJourney.State.PENDING
            }
            ProximiJourney.Stop(id, state)
        }
        return ProximiJourney(kind, stops + unmatched)
    }

    companion object {
        /** [hosted] matched to [places]. Pure; covered by tests. */
        fun of(hosted: ProximiJourney, places: List<VenuePlace>, galleries: List<Gallery> = emptyList()): HostedJourney {
            val byId = LinkedHashMap<String, VenuePlace>()
            for (place in places) byId.putIfAbsent(place.id, place)
            val stops = mutableListOf<io.proximi.gadkit.visit.JourneyStop>()
            val hostIds = LinkedHashMap<String, String>()
            val unmatched = mutableListOf<ProximiJourney.Stop>()
            for (stop in hosted.stops) {
                val poiId = ProximiMap.poiIds(listOf(stop.id), places).firstOrNull()
                val place = poiId?.let { byId[it] }
                if (poiId == null || poiId in hostIds || place == null) {
                    unmatched += stop
                    continue
                }
                hostIds[poiId] = stop.id
                val state = when (stop.state) {
                    ProximiJourney.State.PENDING -> StopState.PENDING
                    ProximiJourney.State.VISITED -> StopState.DONE
                    ProximiJourney.State.SKIPPED -> StopState.SKIPPED
                }
                stops += VisitPlan.stop(place, galleries).copy(state = state)
            }
            return HostedJourney(Journey(stops), hostIds, unmatched)
        }
    }
}

/**
 * The steps that turn the navigator's journey into the host app's journey on the open screen
 * (`VisitController.apply`). Pure; covered by tests.
 */
internal object JourneyUpdate {
    data class StateChange(val stopId: String, val kind: Kind) {
        enum class Kind { MARK_DONE, SKIP, REOPEN }
    }

    /**
     * The state changes that give the stops of [current] the states of [target], reopens first. A
     * stop of [target] that [current] lacks counts as pending. Pending, active and reached are one
     * state for the host app. A done stop the host app skips is reopened first: the navigator does
     * not skip a done stop.
     */
    fun stateChanges(current: Journey, target: Journey): List<StateChange> {
        val states = LinkedHashMap<String, StopState>()
        for (stop in current.stops) states.putIfAbsent(stop.id, stop.state)
        val reopens = mutableListOf<StateChange>()
        val closes = mutableListOf<StateChange>()
        for (stop in target.stops) {
            val state = states[stop.id] ?: StopState.PENDING
            when {
                stop.state == StopState.DONE && state != StopState.DONE ->
                    closes += StateChange(stop.id, StateChange.Kind.MARK_DONE)
                stop.state == StopState.SKIPPED && state != StopState.SKIPPED -> {
                    if (state == StopState.DONE) reopens += StateChange(stop.id, StateChange.Kind.REOPEN)
                    closes += StateChange(stop.id, StateChange.Kind.SKIP)
                }
                stop.state == StopState.PENDING && (state == StopState.DONE || state == StopState.SKIPPED) ->
                    reopens += StateChange(stop.id, StateChange.Kind.REOPEN)
            }
        }
        return reopens + closes
    }

    /**
     * The order of the navigator's `reorderableStops` for the host app's journey, or `null` when it is
     * the current order. The host app's pending stops come in its order. The active stop stays first
     * when the host app closes it, so the route changes once, when it is closed. Stops the host app
     * closes follow, in their current order.
     *
     * @param reorderable the ids of `reorderableStops`, the active stop first.
     * @param pending the ids of the host app's pending stops, in its order.
     * @param closing the ids the host app marks visited or skipped.
     */
    fun order(reorderable: List<String>, activeStopId: String?, pending: List<String>, closing: Set<String>): List<String>? {
        val available = reorderable.toSet()
        val order = mutableListOf<String>()
        if (activeStopId != null && activeStopId in closing && activeStopId in available) order += activeStopId
        for (id in pending) if (id in available && id !in order) order += id
        for (id in reorderable) if (id !in order) order += id
        return if (order == reorderable) null else order
    }
}

/**
 * A change of the host app's journey for the open screen. [revision] tells two equal journeys apart:
 * the screen applies each change, also one equal to the change before it.
 */
internal data class IncomingJourney(val journey: Journey, val revision: Int)
