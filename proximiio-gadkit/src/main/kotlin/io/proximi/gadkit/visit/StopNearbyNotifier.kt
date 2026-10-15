package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.UiText
import io.proximi.gadkit.map.LocalNotice
import io.proximi.gadkit.map.VenueLevel
import io.proximi.map.core.JourneyStop
import io.proximi.map.core.RouteGuidance
import io.proximi.map.core.VenuePosition

/**
 * Posts a local notification when the next stop of the journey (the navigator's active stop) is
 * less than [nearbyMeters] away, on the visitor's level, as iOS `StopNearbyNotifier`. Each stop
 * posts once per visit. The title is the stop title.
 *
 * The distance is the route still to walk (`RouteGuidance.remainingMeters`) while the visitor
 * follows the leg. It is a straight line when there is no guidance or when the visitor is off the
 * route: `remainingMeters` is then measured from the nearest point of the route, not from the
 * visitor.
 *
 * @param post delivers the notice. The visit posts it with `LocalNotices`; tests pass their own.
 */
internal class StopNearbyNotifier(
    private val nearbyMeters: Double = NEARBY_METERS,
    private val post: (LocalNotice) -> Unit,
) {
    private val notifiedStopIds = mutableSetOf<String>()

    /**
     * Posts the notice when [stop] is nearby and has not posted one in this visit. [guidance] is
     * the navigator's guidance for the leg to [stop].
     *
     * @return [stop] when it posted the notice, else `null`.
     */
    fun update(position: VenuePosition?, stop: JourneyStop?, guidance: RouteGuidance?): JourneyStop? {
        if (position == null || stop == null || stop.id in notifiedStopIds) return null
        val nearby = isNearby(
            Coordinate(position.coordinate.latitude, position.coordinate.longitude),
            position.floor?.level,
            Coordinate(stop.coordinate.latitude, stop.coordinate.longitude),
            stop.floor?.level,
            routeMeters = guidance?.takeUnless { it.isOffRoute }?.remainingMeters,
            nearbyMeters = nearbyMeters,
        )
        if (!nearby) return null
        notifiedStopIds += stop.id
        val notice = notice(stop.id, stop.title, nearbyMeters)
        FieldLog.log("notification: ${notice.tag} ${stop.title}")
        post(notice)
        return stop
    }

    companion object {
        /** iOS `GADSettings.stopNearbyMeters`. */
        const val NEARBY_METERS = 5.0
        const val NOTIFICATION_ID = 2_003

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

        /** The notice for the stop. Pure; covered by tests. */
        fun notice(stopId: String, title: String, nearbyMeters: Double = NEARBY_METERS): LocalNotice = LocalNotice(
            tag = "stop-nearby-$stopId",
            title = title,
            body = UiText.Resource("visit_next_stop_nearby", listOf(nearbyMeters.toInt())),
            group = "journey",
        )
    }
}
