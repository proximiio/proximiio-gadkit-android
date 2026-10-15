package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.ringContains

/**
 * The shape of an artwork geofence: a polygon, or a circle around [center], as iOS `ArtworkArea`.
 * [polygon] is GeoJSON `[longitude, latitude]` pairs, as `ProximiioGeofence.polygon`.
 */
internal data class ArtworkArea(val center: Coordinate?, val radius: Double, val polygon: List<List<Double>>? = null) {
    private val ring: List<Coordinate>? =
        polygon?.mapNotNull { pair -> if (pair.size >= 2) Coordinate(pair[1], pair[0]) else null }?.takeIf { it.size >= 3 }

    /** Same containment as the SDK's geofence engine on enter: the polygon when it has three points or more, else the circle. */
    internal fun contains(point: Coordinate): Boolean {
        ring?.let { return ringContains(it, point) }
        if (center == null || (center.latitude == 0.0 && center.longitude == 0.0) || radius <= 0) return false
        return center.distanceTo(point) <= radius
    }
}

/**
 * Arrival at an artwork, as iOS `VisitController` (PM review 2026-10-12): entering the geofence of
 * the active artwork. Nagarro's audio guide reacts to the same enter event, so the map and the
 * guide name the same artwork at the same time. The geofence and the artwork POI share
 * `remote_id`. An artwork the visitor walks past without entering its geofence stays unvisited
 * until it is skipped; another open artwork whose geofence the visitor is inside counts as visited
 * out of order ([GeofenceDwell]). Pure; covered by tests.
 */
internal object ArtworkArrival {
    /**
     * The active artwork stop when its POI has [remoteId], else `null`. [remoteIds] is the
     * `remote_id` of each artwork POI, by POI id.
     */
    internal fun activeStopId(remoteId: String?, journey: Journey, remoteIds: Map<String, String>): String? {
        if (remoteId.isNullOrEmpty()) return null
        val stop = journey.stops.firstOrNull { it.state == StopState.ACTIVE || it.state == StopState.REACHED } ?: return null
        if (stop.kind == StopKind.DETOUR || remoteIds[stop.poiId ?: stop.id] != remoteId) return null
        return stop.id
    }
}

/**
 * How long the visitor has been inside each geofence, as iOS `GeofenceDwell`. Pure; covered by
 * tests.
 */
internal class GeofenceDwell {
    private var insideSince: Map<String, Long> = emptyMap()

    /**
     * Records the stops whose geofence contains the visitor at [nowMillis] and returns those inside
     * for at least [dwellMillis] without a break, sorted. Leaving a geofence resets its time.
     */
    internal fun update(inside: Set<String>, nowMillis: Long, dwellMillis: Long): List<String> {
        insideSince = insideSince.filterKeys { it in inside } + inside.filter { it !in insideSince }.associateWith { nowMillis }
        return insideSince.filterValues { nowMillis - it >= dwellMillis }.keys.sorted()
    }
}
