package io.proximi.gadkit

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.proximi.sdk.core.model.BarrierConfiguration
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

    /**
     * `true`: the journey moves to the next stop when the visitor leaves the active artwork: they are
     * more than [artworkVisitedMeters] from its geofence for [artworkVisitedSeconds], or they enter the
     * geofence of another artwork of the journey (GAD PM, 2026-11-15). Inside the geofence the artwork
     * is reached: the card and the destination marker keep the artwork, and the route shows the leg to
     * the next stop (`JourneyNavigator.drawsNextLegWhileReached`; GAD PM and Nagarro, 2026-10-30).
     * `false`: [artworkAdvanceDelay] after the visitor entered it. iOS `advancesOnArtworkExit`.
     */
    val advancesOnArtworkExit: Boolean = true,
    /**
     * With [advancesOnArtworkExit] `false`: the time between the arrival at an artwork (its geofence)
     * and the route to the next stop.
     */
    val artworkAdvanceDelay: Duration = 2.seconds,
    /**
     * With [advancesOnArtworkExit]: the artwork the visitor arrived at is visited when they are more
     * than this many metres from the border of its geofence…
     */
    val artworkVisitedMeters: Double = 5.0,
    /** …for this long. One position beyond the distance does not end the visit. */
    val artworkVisitedSeconds: Duration = 2.seconds,
    /**
     * Time inside the geofence of an artwork that is not the next stop before it counts as visited
     * out of order. A longer time keeps a visitor who crosses it on the way from marking it.
     */
    val outOfOrderDwell: Duration = Duration.ZERO,
    /**
     * `false`: an artwork the visitor enters out of order becomes the current stop. It is visited by
     * the rules of any artwork the visitor arrived at ([artworkVisitedMeters], the geofence of another
     * artwork, the host app). `true`: it is visited at the entry, as before 1.49.0. Without
     * [advancesOnArtworkExit] it is always visited at the entry (GAD PM, 2026-11-16). iOS
     * `visitsOutOfOrderAtEntry` (1.48.0).
     */
    val visitsOutOfOrderAtEntry: Boolean = false,
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
    /**
     * Time the position stays outside an entered privacy zone before the SDK reports its exit
     * (`ProximiioConfiguration.privacyZoneExitDelay`, SDK 6.0.0-beta.38). The first position after the
     * zone comes this long after the visitor leaves it. Other geofences keep [geofenceExitDelay].
     * `null`: privacy zones wait for [geofenceExitDelay] too. [ProximiMap] sets it when it starts the
     * SDK; [ProximiMapView] runs on the host app's SDK (PM, Monday "Takes quite a long time to get
     * position after a privacy zone", 2026-11-11).
     */
    val privacyZoneExitDelay: Duration? = 1.seconds,

    // Position barriers

    /**
     * How walls drawn as barrier features stop a position that jumps through them
     * (`ProximiioConfiguration.positioning.barriers`, SDK 6.0.0-beta.38). The default is the SDK's:
     * on, 10 positions on a floor before any is stopped, 2 m/s walking speed, 0.4 m from the wall, and
     * the newest position accepted again after 10 stopped positions over 5 s. No effect in a venue
     * without barrier features. [ProximiMap] sets it when it starts the SDK; [ProximiMapView] runs on
     * the host app's SDK. iOS `positionBarriers`.
     */
    val positionBarriers: BarrierConfiguration = BarrierConfiguration(),

    // Notifications

    /**
     * "The next stop of your journey is less than … m away": the distance along the route, or in a
     * straight line without a route.
     */
    val stopNearbyMeters: Double = 5.0,
    /**
     * [ProximiMapEvent.GalleryNearby]: an entrance of the active stop's gallery is less than this
     * many metres away in a straight line, on the visitor's level. A gallery without an entrance POI
     * uses its own POI. A visitor inside the gallery's outline is nearby at any distance. iOS
     * `galleryNearbyMeters`.
     */
    val galleryNearbyMeters: Double = 5.0,
    /**
     * The amenity of the gallery entrance POIs. An entrance names its gallery in
     * `metadata.gallery_id`; a gallery can have several (GAD PM, 2026-11-15). iOS
     * `galleryEntranceAmenityID`.
     */
    val galleryEntranceAmenityId: String = "7405e6e6-d9f6-4146-adda-de6adbf4a8a1:3c274297-2461-4c4b-b380-262d76a5eca5",

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
    /**
     * The walked part of the route moves only while the position is within this many metres of the
     * leg; further away it stays where it was (`RouteFollowRules.progressCorridorMeters`). The remaining
     * distance and the instructions follow every position. `null`: the walked part follows the position
     * at any distance (PM, Monday "Route marked as walked too far", 2026-10-30). iOS
     * `progressCorridorMeters`.
     */
    val progressCorridorMeters: Double? = 3.0,
    /**
     * A route ends where its stop projects onto the path network. The line goes on in a straight line
     * to the stop when the stop is at most this many metres from that point. The remaining distance
     * and the walking time include that segment. A leg to a gallery's POI (gallery first) ends on the
     * path. 0: every route ends on the path. The SDK routes to a stop at most 15 m from a path
     * (`WayfindingRouter.DEFAULT_SNAP_THRESHOLD_METERS`), so 15 extends every route (user, 2026-11-09). iOS
     * `routeToStopMaximumMeters`.
     */
    val routeToStopMaximumMeters: Double = 15.0,
    /**
     * A route starts where its origin projects onto the path network. The line starts at the origin,
     * in a straight line to that point, when the origin is at most this many metres from it. The origin
     * is the visitor's position when the route is computed; the segment does not move with later
     * positions. The remaining distance and the walking time include it. 0: every route starts on the
     * path (GAD PM, Monday "Routing Tongari-Kun -> Goroka man", 2026-11-15). iOS
     * `routeFromOriginMaximumMeters` (1.44.0).
     */
    val routeFromOriginMaximumMeters: Double = 15.0,

    // Map

    /**
     * Zoom of "Centre on me", of the first position and of the follow, in personalised and curated
     * journeys, while the visitor is in no gallery. The follow eases to it from [startZoom]. 19 since
     * 1.56.0, was 19.5 (operator, K-39, 2026-11-19).
     */
    val followZoom: Double = 19.0,
    /**
     * Zoom of the follow while the visitor is in a gallery, in personalised and curated journeys, or
     * `null` for [followZoom]. 20 since 1.65.1 (GAD PM, Monday 2026-11-20; iOS 1.64.0); 21.5 since 1.59.1 (GAD PM, Monday "Zoom level", 2026-11-20: 22 is too close; iOS 1.59.0); 22 since 1.56.0 (operator, K-39, 2026-11-19: the map zooms in when the
     * visitor enters a gallery); `null` before, and personalised journeys only. Above [maximumZoom]
     * the map stops at [maximumZoom].
     */
    val galleryFollowZoom: Double? = 20.0,
    /**
     * The journey map shows the visitor at this zoom for [startZoomDuration] before it follows them:
     * when the journey starts, on the first fix and when the position comes back. `null` follows at
     * once (PM, Monday "Zoom level at start + navigation", 2026-10-29 and 2026-10-30). iOS `startZoom`.
     */
    val startZoom: Double? = 17.0,
    /** How long the journey map stays at [startZoom]. iOS `startZoomSeconds`. */
    val startZoomDuration: Duration = 2.seconds,
    /**
     * The height over which the journey map fades out at the top of its card, under the Map / List
     * control. The faded part shows what the host app draws behind [ProximiMapView]. 0: no fade. iOS
     * `mapTopFadeHeight` (points).
     */
    val mapTopFadeHeight: Dp = 110.dp,
    /** The height over which the journey map fades out at the bottom of its card, under the stop card. 0: no fade. iOS `mapBottomFadeHeight`. */
    val mapBottomFadeHeight: Dp = 170.dp,
    /**
     * The farthest the visitor can zoom out, or `null` for the map library's limit. MapLibre zoom
     * levels. The camera keeps to the range in every frame the map sets too (`MapOptions`).
     */
    val minimumZoom: Double? = null,
    /** The farthest the visitor can zoom in, or `null` for the map library's limit. */
    val maximumZoom: Double? = null,
    /**
     * The box the screen stays inside, or `null` for no limit. It holds for gestures and for every
     * camera move the map makes, on the journey map and the Explore Museum map
     * (`MapOptions.cameraBounds`, map 6.0.0-beta.39). The map stops zooming out where the box
     * covers the view, so a [minimumZoom] below that zoom is not reached. The default is the PM's
     * box around the museum (Monday "Zoom out limit", 2026-11-10). iOS `cameraBounds`.
     */
    val cameraBounds: GadCameraBounds? = GadCameraBounds.GUGGENHEIM_ABU_DHABI,
    /**
     * `true` picks the first gallery on a floor and the gallery after a finished one by routed
     * walking time (`JourneyOrderStrategy.FLOOR_THEN_GALLERY_ROUTED`, map 6.0.0-beta.39). `false`
     * picks them by straight-line distance, as before. A stop without a route uses the straight
     * line in both (PM, Monday "First stop over birds flight vs along the route"). iOS
     * `ordersGalleriesByWalkingTime`.
     */
    val ordersGalleriesByWalkingTime: Boolean = true,
    /**
     * `false` draws the position dot on each fix. `true` slides it toward each fix (the map
     * library's adaptive smoothing), which shows a fix a moment later (PM, Monday "Visual delay",
     * 2026-10-23).
     */
    val smoothsPosition: Boolean = false,
    /**
     * `true` shows journey stop markers that would overlap as one marker with a badge holding the
     * number of the others (PM, Monday "Clustering", 2026-10-29). iOS `groupsStopMarkers`.
     */
    val groupsStopMarkers: Boolean = true,
    /**
     * The opacity of the position dot without new positions and in a privacy zone, 0 to 1. The dot
     * turns grey after the map library's stale time and stays at this opacity; its accuracy ring does
     * not grow. 0 hides the dot (user, 2026-11-15). iOS `positionWithoutFixesOpacity` (1.45.0).
     */
    val positionWithoutFixesOpacity: Double = 0.45,
    /**
     * `true` shows a journey artwork without a numbered marker as a dot without a number: an artwork
     * in a gallery the visitor is not in, a skipped artwork, and every artwork while the route leads
     * to a gallery. `false`, the default since 1.44.0, shows nothing there (GAD PM, 2026-11-15: on
     * hold). iOS `showsStopDots`.
     */
    val showsStopDots: Boolean = false,
    /**
     * `true` shows the number of a journey artwork in its marker on the map, on the stop card and in
     * the list. `false`, the default since 1.50.0, shows the same circles without the number
     * (operator, 2026-11-17). The badge of grouped markers keeps its count ([groupsStopMarkers]).
     * iOS `showsStopNumbers`.
     */
    val showsStopNumbers: Boolean = false,
    /**
     * `true` draws a pin in the marker of a journey artwork on the map and, since 1.60.0, in the badge
     * of its row in the journey list, while [showsStopNumbers] is `false`: white on the red circle of
     * the current artwork, dark in the others (RPR design; GAD PM,
     * Monday "Icons for personalized routes", 2026-11-20). `false` shows the circles alone, as before
     * 1.59.1. The detour's marker, a skipped artwork and the stop dots have no pin. iOS
     * `showsStopIcons` (1.59.0, 1.60.0).
     */
    val showsStopIcons: Boolean = true,
    /**
     * `true` draws a gallery's name label on the journey map in the shape of the gallery's type, as
     * the museum's floor plan does: a pill for a single-artist gallery, a rectangle with square
     * corners for a thematic one (operator, 2026-11-20). The type is the title of the amenity of the
     * gallery's POI. A gallery of no known type has the pill. `false` draws every label as a pill, as
     * before 1.61.0. `false` since 1.65.1 (GAD PM, Monday 2026-11-20: every label is a pill; iOS
     * 1.64.0). iOS `shapesGalleryLabelsByType` (1.61.0).
     */
    val shapesGalleryLabelsByType: Boolean = false,
    /**
     * `true` draws the amenity kinds the library knows (toilet, café, restaurant, help desk, prayer
     * room, stairs, lift, museum shop, theatre) with the icon of their amenity in the venue data, so
     * the icons are edited in the Proximi portal (GAD PM, 2026-11-22). The icon is a dark glyph on a
     * transparent square; the library tints it and, on the maps, draws it in the white circle marker.
     * The bundled glyph is drawn when the amenity has no icon, and in lists and chips when two
     * amenities of one kind have a place. `false` draws the bundled glyphs everywhere, as up to
     * 1.67.0. iOS `usesVenueAmenityIcons` (1.68.0).
     */
    val usesVenueAmenityIcons: Boolean = true,
    /**
     * The transport of the wristband positions for the binding the library creates (`ProximiMap`).
     * [GadWristbandTransport.AUTOMATIC] since 1.69.0: on the DCT production installation the
     * client's Azure Application Gateway buffers Server-Sent Events, the positions came in bursts
     * and the position dot went grey (2026-11-22). A host app that creates its own
     * `BlueiotWristbandBinding` sets `BlueiotBindingConfiguration.transport` itself.
     * iOS `wristbandTransport` (1.69.0).
     */
    val wristbandTransport: GadWristbandTransport = GadWristbandTransport.AUTOMATIC,
    /**
     * `true` orders the stops ahead of a personalised journey again from the visitor when an artwork is
     * marked visited while the visitor stands in another gallery than the next stop's, and that gallery
     * has an artwork still to visit (GAD PM, Monday "Re-routing going from gallery 104 to 102",
     * 2026-11-15). `false` keeps the order, as before. iOS `ordersFromVisitorGallery` (1.46.0).
     */
    val ordersFromVisitorGallery: Boolean = true,
    /**
     * `true` draws the leg of a personalised journey again from the visitor while they walk in the
     * gallery of the current artwork, more than [galleryRouteRefreshMeters] off the leg, at most every
     * [galleryRouteRefreshSeconds]. With `ProximiMapView.Option.PROGRESS` the stops ahead are ordered
     * from the visitor first, so the leg leads to the nearest artwork. No off-route warning shows
     * (GAD PM, field test of 2026-11-17: "The route could be recalculated more frequently"). `false`
     * keeps the leg up to [offRouteMeters], as before. iOS `refreshesRouteInGallery` (1.51.0).
     */
    val refreshesRouteInGallery: Boolean = true,
    /** Metres off the leg, in the gallery of the current artwork, beyond which [refreshesRouteInGallery] draws the leg again. */
    val galleryRouteRefreshMeters: Double = 3.0,
    /** The shortest time between two leg refreshes of [refreshesRouteInGallery]. */
    val galleryRouteRefreshSeconds: Duration = 3.seconds,
    /**
     * `true` orders the stops ahead of a personalised journey again from the visitor when they enter
     * the gallery of an artwork still to visit that is not the gallery of the current artwork, once
     * per entry (GAD PM, field test of 2026-11-17: "It should also re-route whenever a gallery geofence
     * is entered"). Needs `ProximiMapView.Option.PROGRESS`. `false` keeps the order, as before. iOS
     * `ordersAtGalleryEntry` (1.51.0).
     */
    val ordersAtGalleryEntry: Boolean = true,
    /**
     * How long the visitor's position lies in a gallery before [ordersAtGalleryEntry] and
     * [ordersVisitorGalleryFirst] count the visitor in it. A position that crosses a border for a
     * shorter time changes no order. Zero counts the first fix. iOS `galleryEntryOrderSeconds` (1.52.0).
     */
    val galleryEntryOrderSeconds: Duration = 3.seconds,
    /**
     * Personalised journeys: `true` puts the artworks of the gallery the visitor is in first in every
     * order from the visitor, also when an artwork of another gallery is nearer by the route. So the
     * entry of a gallery ([ordersAtGalleryEntry]), a resume and an order off the route lead to an
     * artwork of that gallery. `false`: the nearest artwork by the route goes first, as in 1.51.0 (GAD
     * PM's field test and Nagarro's bug list, 2026-11-18). iOS `ordersVisitorGalleryFirst` (1.52.0).
     */
    val ordersVisitorGalleryFirst: Boolean = true,
    /**
     * `ProximiMapView` with `Option.PROGRESS`, personalised journeys: `true` puts the visited and
     * skipped stops of the journey the host app passes before the pending ones, each group in the host
     * app's order, and the view writes that order back. A host app that stores the states but passes
     * its first order after a restart showed a visited artwork behind open ones in the list (GAD PM,
     * on site, 2026-11-19). `false` keeps the host app's order. iOS `ordersDoneStopsFirst` (1.57.0).
     */
    val ordersDoneStopsFirst: Boolean = true,
    /**
     * `true` marks an artwork the visitor has arrived at visited when the visitor enters the geofence
     * of another artwork of the journey and is outside its own. `false`: only [artworkVisitedMeters]
     * and [artworkVisitedSeconds] mark it visited. iOS `visitsArtworkAtAnotherArtwork` (1.52.0).
     */
    val visitsArtworkAtAnotherArtwork: Boolean = true,
    /**
     * Personalised journeys, the checks that run while the visitor walks ([refreshesRouteInGallery]
     * and the order off the route): another artwork becomes the current stop only when it is this
     * many metres nearer to the visitor than the current stop, in a straight line. An artwork of the
     * visitor's gallery takes the place of one outside it at any distance. 0 takes every other order,
     * as before 1.53.0, also between two artworks at the same distance. iOS `orderSwitchMinimumMeters`
     * (1.53.0).
     */
    val orderSwitchMinimumMeters: Double = 3.0,
    /**
     * Personalised journeys: in the gallery of the current artwork, the nearest open artwork of that
     * gallery becomes the current stop when it is [orderSwitchMinimumMeters] nearer to the visitor
     * than the current stop, in a straight line, also for a visitor on the leg. Checked at most every
     * [galleryRouteRefreshSeconds]. Needs `ProximiMapView.Option.PROGRESS`. `false`: the stop changes
     * only off the leg ([refreshesRouteInGallery]), as before 1.54.0. iOS
     * `ordersNearestArtworkInGalleryFirst` (1.54.0).
     */
    val ordersNearestArtworkInGalleryFirst: Boolean = true,
    /**
     * Personalised journeys: while the visitor is in no gallery of the journey (the open area between
     * the galleries), the stops ahead are ordered again from the visitor when that order starts with
     * another artwork and saves [openAreaOrderMinimumMeters] of walking by the route. The journey then
     * leads to the nearer gallery (GAD PM, Monday "Re-routing logic: re-route when user is in open
     * areas to closest gallery", 2026-11-20). Needs `ProximiMapView.Option.PROGRESS`. `false`: outside
     * the galleries the order changes only after a visit and after a detour, as before 1.62.0. iOS
     * `ordersInOpenArea` (1.61.1).
     */
    val ordersInOpenArea: Boolean = true,
    /**
     * The journey map follows the visitor again each time they enter or leave a gallery, when a
     * gesture left the camera free (GAD PM, Monday "map doesn't follow user", 2026-11-20). The next
     * gesture frees the camera again. Not while the journey is paused. `false`: only "Centre on me"
     * takes the follow again, as before 1.63.0. iOS `followsAgainAtGalleryChange` (1.63.0).
     */
    val followsAgainAtGalleryChange: Boolean = true,
    /**
     * `true`: the journey map draws a line from the visitor to the nearest point of the route ahead,
     * at every position, on the visitor's floor (GAD PM, Monday "Route drawing feels off", 2026-11-20:
     * the route did not start at the blue dot). The route itself is not computed again. No line
     * within 2 m of the route's end, none after the arrival, and none further than
     * [routeConnectorMaximumMeters] from the route. `false`, the default (the PM parked the item on
     * 2026-11-20): no line, as before 1.65.1. iOS `connectsRouteToPosition` (1.65.0, off since 1.65.1).
     */
    val connectsRouteToPosition: Boolean = false,
    /**
     * The longest line [connectsRouteToPosition] draws, in metres. Further from the route there is
     * no line: a long one crosses walls. iOS `routeConnectorMaximumMeters` (1.65.0).
     */
    val routeConnectorMaximumMeters: Double = 6.0,
    /**
     * How many metres of walking the order from the visitor saves before [ordersInOpenArea] takes it.
     * A small value changes the destination at every step between two galleries. iOS
     * `openAreaOrderMinimumMeters` (1.61.1).
     */
    val openAreaOrderMinimumMeters: Double = 15.0,
    /**
     * The shortest time between two checks of [ordersInOpenArea]. Each check asks the map for an order
     * by walking time. iOS `openAreaOrderSeconds` (1.61.1).
     */
    val openAreaOrderSeconds: Duration = 20.seconds,
    /**
     * The visitor has arrived at the current artwork after [artworkArrivalMarginSeconds] within this
     * many metres of its geofence, outside it and inside no geofence of another open artwork of the
     * journey. 0 (default; GAD PM, 2026-11-18: larger geofences in the venue data instead): only a
     * fix inside the geofence is the arrival. The visit ends beyond [artworkVisitedMeters], which must
     * be larger. iOS `artworkArrivalMarginMeters` (1.55.0, 0 since 1.55.1).
     */
    val artworkArrivalMarginMeters: Double = 0.0,
    /** How long the visitor stays within [artworkArrivalMarginMeters] before the arrival. iOS `artworkArrivalMarginSeconds`. */
    val artworkArrivalMarginSeconds: Duration = 3.seconds,
    /**
     * Gallery first: an artwork that led to the artwork because the visitor was in its gallery leads
     * to the gallery again when the visitor is more than this many metres outside the gallery's
     * outline, or on another floor (1.51.1). iOS `galleryLeftMeters` (1.51.0).
     */
    val galleryLeftMeters: Double = 5.0,
    /**
     * Journey list: `true` makes a swipe on an artwork ask for the skip at once
     * ([ProximiMapEvent.SkipRequested], and the dialog with `ProximiMapView.Option.SKIP_PROMPT`); the
     * row slides back. `false`: the swipe reveals the skip button, and a tap on it asks for the skip
     * (Nagarro, 2026-11-18: a skip with fewer steps). `true` since 1.65.1 (GAD PM and Nagarro, Monday
     * 2026-11-20: no second step; iOS 1.64.0). iOS `skipsAtSwipe` (1.51.0).
     */
    val skipsAtSwipe: Boolean = true,
    /**
     * The journey list while the journey is paused (`ProximiMapView`'s `isPaused`, "Add a stop").
     * `false`, the default: a swipe on an artwork skips nothing, sends no event and shows "Resume your
     * journey to skip the artwork" with "Dismiss"; the text also replaces the swipe hint (GAD PM,
     * Monday "Swipe to skip disabled when navigation is paused", 2026-11-20). `true` keeps the swipe,
     * as before 1.59.1. iOS `skipsWhilePaused` (1.59.0, 1.59.1).
     */
    val skipsWhilePaused: Boolean = false,
    /**
     * The time between two uploads of the field log to the relay-api. `ProximiMap` starts the uploads
     * in `start()` with the relay base URL and app token of its `GadConfiguration`. Zero sends
     * nothing. A host app on `ProximiMapView` calls `FieldLog.startUploading` itself. iOS
     * `fieldLogUploadInterval` (1.49.0).
     */
    val fieldLogUploadInterval: Duration = 900.seconds,
    /**
     * The time the heading-up map takes to turn to a new heading (`MapOptions.bearingSettlingTime`,
     * map default 0.9 s). Nagarro found the rotation too fast (2026-10-29). iOS `bearingSettlingTime`.
     */
    val bearingSettlingTime: Duration = 1.5.seconds,
    /**
     * Heading changes smaller than this, in degrees, do not turn the map (`MapOptions.bearingDeadband`,
     * map default 0). iOS `bearingDeadband`.
     */
    val bearingDeadband: Double = 5.0,
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
    /**
     * The Explore Museum map opens on the spaces whose centre is at most this far from the median
     * centre of all spaces, so a space far from the building does not make the museum small on the
     * screen (GAD PM, on site, 2026-11-19). `null` frames every space, as before 1.58.0. iOS
     * `exploreFramingMaximumMeters` (1.58.0).
     */
    val exploreFramingMaximumMeters: Double? = 150.0,

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

/** A box in degrees that limits the map camera ([GadSettings.cameraBounds]). iOS `GADCameraBounds`. */
public data class GadCameraBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    public companion object {
        /** The PM's box around the Guggenheim Abu Dhabi (Monday "Zoom out limit", 2026-11-10). */
        public val GUGGENHEIM_ABU_DHABI: GadCameraBounds = GadCameraBounds(
            south = 24.53557985, west = 54.39348543, north = 24.54644005, east = 54.40758853,
        )
    }
}

/**
 * How the wristband positions reach the phone from the relay-api (`GET /v1/me/position`).
 * iOS `GADSettings.WristbandTransport`.
 */
public enum class GadWristbandTransport {
    /** Server-Sent Events. A proxy that buffers responses delivers them in bursts. */
    SERVER_SENT_EVENTS,

    /** WebSocket only. */
    WEB_SOCKET,

    /**
     * WebSocket first. Server-Sent Events when a WebSocket cannot be opened; an open WebSocket that
     * drops is opened again as a WebSocket.
     */
    AUTOMATIC,
}
