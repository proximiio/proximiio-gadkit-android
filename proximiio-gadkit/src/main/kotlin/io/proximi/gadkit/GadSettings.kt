package io.proximi.gadkit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The times and distances of the map and journey screens, and the geofence notifications, as iOS
 * `GADSettings`. The host app sets them in [GadConfiguration.settings]. The defaults are the PM's
 * values as of 2026-10-14. Pass the arguments by name: new settings can come in minor versions.
 */
public data class GadSettings(
    // Wristband tracking

    /**
     * Time without positioning before `ProximiMapEvent.TrackingChanged(LOST_PLACE)`. The SDK reports
     * positioning unavailable about 10 s after the last fix, so 110 s is about 2 min after it.
     */
    val trackingLostAfter: Duration = 110.seconds,
    /**
     * Time without positioning before `ProximiMapEvent.TrackingChanged(STOPPED_RESPONDING)`. Must
     * be more than [trackingLostAfter]. 170 s is about 3 min after the last fix.
     */
    val trackingStoppedAfter: Duration = 170.seconds,

    // Arrival

    /** Time between the arrival at an artwork (its geofence) and the route to the next stop. */
    val artworkAdvanceDelay: Duration = 2.seconds,
    /**
     * Time inside the geofence of an artwork that is not the next stop before it counts as visited
     * out of order. A longer time keeps a visitor who crosses it on the way from marking it.
     */
    val outOfOrderDwell: Duration = Duration.ZERO,
    /** An amenity has no geofence. It is reached within 2.5 × the position accuracy, at least this many metres… */
    val amenityArrivalMinimumMeters: Double = 5.0,
    /** …and at most this many metres… */
    val amenityArrivalMaximumMeters: Double = 10.0,
    /** …for this time. */
    val amenityArrivalDwell: Duration = 5.seconds,

    // Notifications

    /**
     * "The next stop of your journey is less than … m away": the distance along the route, or in a
     * straight line without a route.
     */
    val stopNearbyMeters: Double = 5.0,

    // Route

    /** Metres from the route before "You've left your route". */
    val offRouteMeters: Double = 30.0,
    /** Time off the route before "You've left your route", at any distance. iOS `offRouteSeconds`. */
    val offRouteAfter: Duration = 300.seconds,
    /**
     * The position dot is drawn on the route while it is within this many metres of it. Display
     * only; guidance and arrival use the real position.
     */
    val routeSnapMeters: Double = 0.5,

    // Map

    /** Zoom of "Centre on me" and of the first position. */
    val followZoom: Double = 21.0,

    // Lift

    /**
     * Time of fixes inside a lift outline before the visitor counts as riding it. A shorter stay is
     * walking past the lift.
     */
    val liftRideDwell: Duration = 3.seconds,
    /** Consecutive fixes outside the lift outline that end the ride. */
    val liftExitFixes: Int = 2,

    // Floors without positioning

    /**
     * Floors without positioning. A route there leads to the lift or stairs, shows the lift or
     * stairs view, then shows that floor with the rest of the route. In stage 1 the GAD level 0 has
     * no positioning (PM 2026-10-14).
     */
    val levelsWithoutPositioning: Set<Int> = setOf(0),
    /** Metres from the lift or stairs to a floor without positioning at which the visitor counts as there. */
    val levelChangerReachedMeters: Double = 5.0,
    /** Time of the lift or stairs view before the map shows the floor without positioning. iOS `levelChangeViewSeconds`. */
    val levelChangeViewDuration: Duration = 5.seconds,
    /**
     * Time after which the map follows the visitor again when the destination on a floor without
     * positioning did not change. iOS `floorWithoutPositioningMaximumSeconds`.
     */
    val floorWithoutPositioningMaximum: Duration = 1800.seconds,

    // "Probably at …"

    /** Positions less accurate than this (metres) near the next stop ask "Are you at …?". */
    val uncertainAccuracyMeters: Double = 8.0,
    /** Time a question the visitor answered stays quiet. */
    val locationQuestionQuietPeriod: Duration = 120.seconds,

    // Journey order

    /** Time a new personalised journey waits for a fix before it is ordered without one. */
    val firstFixWait: Duration = 5.seconds,
    /** Time after the start in which the first fix orders a journey that was ordered without one. */
    val lateOrderWindow: Duration = 60.seconds,

    // Journey notification

    /**
     * The shortest gap between two updates of the instruction in the ongoing journey notification
     * (iOS `liveActivityMinimumInterval`, the Live Activity). A new step is sent at once.
     */
    val journeyNotificationMinimumInterval: Duration = 5.seconds,

    // Notifications

    /**
     * Posts a notification for every enter and exit of a geofence with a name, and asks for the
     * notification permission once a wristband is connected (`ProximiMapHost`). The "next stop"
     * notification of the journey ([stopNearbyMeters]) does not depend on it.
     */
    val postsGeofenceNotifications: Boolean = true,
) {
    public companion object {
        /** The defaults. */
        public val STANDARD: GadSettings = GadSettings()
    }
}
