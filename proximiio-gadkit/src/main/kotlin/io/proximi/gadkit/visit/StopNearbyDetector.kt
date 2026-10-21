package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.map.VenueLevel
import io.proximi.map.core.JourneyStop
import io.proximi.map.core.RouteGuidance
import io.proximi.map.core.VenuePosition

/**
 * Detects when the next stop of the journey (the navigator's active stop) is less than
 * [nearbyMeters] away, on the visitor's level, as iOS `StopNearbyDetector`. Each stop counts once
 * per visit. `VisitViewModel` reports it as [VisitEvent.StopNearby]; the host app can post a
 * notification for it.
 *
 * The distance is the route still to walk (`RouteGuidance.remainingMeters`) while the visitor
 * follows the leg. It is a straight line when there is no guidance or when the visitor is off the
 * route: `remainingMeters` is then measured from the nearest point of the route, not from the
 * visitor.
 */
internal class StopNearbyDetector(private val nearbyMeters: Double = NEARBY_METERS) {
    private val reportedStopIds = mutableSetOf<String>()

    /**
     * [stop] when it is nearby for the first time in this visit, else `null`. [guidance] is the
     * navigator's guidance for the leg to [stop].
     */
    fun update(position: VenuePosition?, stop: JourneyStop?, guidance: RouteGuidance?): JourneyStop? {
        if (position == null || stop == null || stop.id in reportedStopIds) return null
        val nearby = isNearby(
            Coordinate(position.coordinate.latitude, position.coordinate.longitude),
            position.floor?.level,
            Coordinate(stop.coordinate.latitude, stop.coordinate.longitude),
            stop.floor?.level,
            routeMeters = guidance?.takeUnless { it.isOffRoute }?.remainingMeters,
            nearbyMeters = nearbyMeters,
        )
        if (!nearby) return null
        reportedStopIds += stop.id
        FieldLog.log("stop nearby: ${stop.title}")
        return stop
    }

    companion object {
        /** iOS `GADSettings.stopNearbyMeters`. */
        const val NEARBY_METERS = 5.0

        /**
         * `true` when the stop is on the visitor's level and less than [nearbyMeters] away:
         * [routeMeters] when set, else the straight line from [position]. An unknown level
         * matches any level. Pure; covered by tests.
         */
        fun isNearby(
            position: Coordinate,
            level: Double?,
            stop: Coordinate,
            stopLevel: Double?,
            routeMeters: Double? = null,
            nearbyMeters: Double = NEARBY_METERS,
        ): Boolean {
            if (!VenueLevel.matches(level, stopLevel)) return false
            return (routeMeters ?: position.distanceTo(stop)) < nearbyMeters
        }
    }
}
