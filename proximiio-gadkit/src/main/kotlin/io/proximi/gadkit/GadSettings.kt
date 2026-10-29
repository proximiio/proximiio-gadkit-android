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

    // Geofences

    /**
     * Time the position stays outside an entered geofence or privacy zone before the SDK reports the
     * exit (`ProximiioConfiguration.geofenceExitDelay`, SDK 6.0.0-beta.29, whose default is 3 s). A
     * position back inside within this time reports nothing. Enters are reported at once.
     * [ProximiMap] sets it when it starts the SDK; [ProximiMapView] runs on the host app's SDK, which
     * has its own value. 0 reports the exit at the first position outside.
     */
    val geofenceExitDelay: Duration = 5.seconds,

    // Notifications

    /**
     * "The next stop of your journey is less than … m away": the distance along the route, or in a
     * straight line without a route.
     */
    val stopNearbyMeters: Double = 5.0,

    // Route

    /**
     * Metres from the leg to the current destination before "Looks like you've left your route", also
     * where a later leg passes; and from every leg still to walk, for 3 fixes, before the navigator's
     * `LeftRoute` (PM, Monday "Personalized tour: off-route and re-route behaviour", 2026-10-28).
     */
    val offRouteMeters: Double = 12.0,
    /** Time off the route before "Looks like you've left your route", at any distance. iOS `offRouteSeconds`. */
    val offRouteAfter: Duration = 300.seconds,
    /**
     * How long "Looks like you've left your route" stays open, unless the visitor closes it. iOS
     * `offRouteWarningSeconds`.
     */
    val offRouteWarningDuration: Duration = 10.seconds,
    /**
     * Time on a floor the route does not take before the visitor counts as off the route. iOS
     * `wrongFloorSeconds`.
     */
    val wrongFloorAfter: Duration = 10.seconds,
    /**
     * The position dot is drawn on the route while it is within this many metres of it. Display
     * only; guidance and arrival use the real position.
     */
    val routeSnapMeters: Double = 0.5,

    // Map

    /**
     * Zoom of "Centre on me", of the first position and of the follow (PM, Monday "Curated tours:
     * zoom level", 2026-10-27; RPR, "Need to determine correct zoom levels on-site", 2026-10-28).
     */
    val followZoom: Double = 18.8,
    /**
     * Zoom of the follow while the visitor is in a gallery during a personalised journey, or `null`
     * for [followZoom] (PM, Monday "Navigation to a gallery", 2026-10-27).
     */
    val galleryFollowZoom: Double? = 20.0,
    /**
     * The journey map shows the visitor at this zoom for [startZoomDuration] before it follows them:
     * when the journey starts, on the first fix and when the position comes back. `null` follows at
     * once (PM, Monday "Zoom level at start + navigation", 2026-10-29). iOS `startZoom`.
     */
    val startZoom: Double? = 18.0,
    /** How long the journey map stays at [startZoom]. iOS `startZoomSeconds`. */
    val startZoomDuration: Duration = 2.seconds,
    /**
     * The farthest the visitor can zoom out, or `null` for the map library's limit. MapLibre zoom
     * levels. The camera keeps to the range in every frame the map sets too (`MapOptions`).
     */
    val minimumZoom: Double? = null,
    /** The farthest the visitor can zoom in, or `null` for the map library's limit. */
    val maximumZoom: Double? = null,
    /**
     * `false` draws the position dot on each fix. `true` slides it toward each fix (the map
     * library's adaptive smoothing), which shows a fix a moment later (PM, Monday "Visual delay",
     * 2026-10-23).
     */
    val smoothsPosition: Boolean = false,
    /**
     * The longest time the maps show their spinner while the map library draws the venue. The
     * spinner goes when the venue's features are on the map, when the map reports an error, or
     * after this time.
     */
    val mapLoadingTimeout: Duration = 20.seconds,

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
     * After the lift or stairs view the map shows the floor the ride ends on until a fix on another
     * floor than the ride's start, or a fix on that floor this many metres from the lift or stairs
     * (PM, Monday "Elevator/Staircase animation", 2026-10-28).
     */
    val rideFloorHoldMeters: Double = 20.0,
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
    /**
     * With [postsGeofenceNotifications]: `false` posts no geofence notification, while the
     * notification permission is still asked for.
     */
    val notifiesGeofenceEvents: Boolean = true,

    // Venue data

    /**
     * No effect since 1.9.0. The screens follow `Proximiio.venueDataChanges()`, and the SDK's audit
     * sync brings the changed features (`ProximiioConfiguration.syncInterval`).
     */
    @Deprecated("No effect: the venue data follows Proximiio.venueDataChanges(). Set ProximiioConfiguration.syncInterval instead.")
    val venueDataRefreshInterval: Duration = 300.seconds,

    // Map

    /**
     * The smallest width and height in metres of the area the map shows for an amenity picked in the
     * menu, so a near amenity does not zoom the map in further than this.
     */
    val amenityFramingMinimumMeters: Double = 40.0,

    // Gallery first

    /**
     * Gallery first: a leg leads to an artwork's gallery only when the artwork lies in the gallery's
     * geofence on the same floor, or at most this many metres outside it. Otherwise the leg ends at
     * the artwork. In the GAD data of 2026-10-19, 34 artworks lie 5–29 m outside their gallery's
     * geofence.
     */
    val galleryFirstMarginMeters: Double = 50.0,
) {
    public companion object {
        /** The defaults. */
        public val STANDARD: GadSettings = GadSettings()
    }
}
