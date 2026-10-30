package io.proximi.gadkit

import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.visit.VisitMode
import io.proximi.gadkit.wristband.TrackingHealth
import kotlin.time.Duration

// The values the host app exchanges with `ProximiMap`, as iOS `ProximiMapTypes.swift`: the journey
// it sets, the journey and wristband states it shows, the events it receives and the errors.

/**
 * A journey for [ProximiMap.setJourney]: from the audio guide (personalised) or a curated tour.
 *
 * @property title shown on Journey ready and in [JourneySummary].
 * @property summary the line under the title, for example "6 stops". `null` shows the number of stops.
 * @property artworks the artworks in visit order: collection ids (`remote_id`, for example
 *   "GAD.2010.00033") or Proximi.io POI ids. Ids that match no POI are left out.
 */
public data class JourneyPlan(
    public val title: String,
    public val summary: String? = null,
    public val kind: JourneyKind = JourneyKind.PERSONALISED,
    public val artworks: List<String>,
)

/**
 * The stored journey, for the host app's own screens, for example a journey card.
 *
 * @property stopCount the artworks of the journey. Detours to amenities do not count.
 * @property remainingStopCount the artworks not visited or skipped yet. 0 when the journey is finished.
 * @property isStarted `true` once a stop was visited or skipped.
 */
public data class JourneySummary(
    public val title: String,
    public val summary: String?,
    public val kind: JourneyKind,
    public val stopCount: Int,
    public val remainingStopCount: Int,
    public val isStarted: Boolean,
)

/** The phone's wristband session. */
public sealed interface WristbandStatus {
    /**
     * No session. [notice] says why the last session ended, for example "Your wristband was scanned
     * by another phone."; `null` when none has ended since the launch.
     */
    public data class NotConnected(public val notice: String? = null) : WristbandStatus

    /** A [ProximiMap.connectWristband] request is in flight. */
    public data object Connecting : WristbandStatus

    /** The phone follows the wristband's positions. */
    public data object Connected : WristbandStatus
}

/**
 * A journey stop, an amenity or a detour, in [ProximiMapEvent].
 *
 * @property id the Proximi.io POI id.
 * @property artworkId the artwork's collection id (`remote_id`), or `null` for an amenity.
 * @property level the floor, or `null` when the POI has none.
 */
public data class ProximiStop(
    public val id: String,
    public val title: String,
    public val artworkId: String? = null,
    public val level: Int? = null,
)

/**
 * A geofence of the venue, in [ProximiMapEvent.GeofenceEntered] and [ProximiMapEvent.GeofenceExited].
 *
 * @property id the Proximi.io geofence id.
 * @property type `metadata.geofence_type`: `artwork`, `gallery`, or `null`.
 * @property artworkId the artwork's collection id (`remote_id`) of an artwork geofence.
 */
public data class ProximiGeofence(
    public val id: String,
    public val name: String? = null,
    public val type: String? = null,
    public val artworkId: String? = null,
)

/**
 * A place the host app routes the visitor to on [ProximiMapView], for example the place of the audio
 * guide's proximiio-mcp answer (`route_to_place`, `route_to_nearest_amenity`). iOS
 * `ProximiDestination`.
 *
 * @property poiId the Proximi.io POI id: the feature id, as in [ProximiStop.id], or its
 *   `properties.id`, which proximiio-mcp returns. On GAD data the feature id is
 *   `<organisation id>:<properties.id>`.
 */
public data class ProximiDestination(public val poiId: String) {
    /** Why [ProximiMapView] does not route to a destination ([ProximiMapEvent.DestinationUnavailable]). */
    public enum class Failure {
        /** The id is not a place of the venue. */
        UNKNOWN_PLACE,

        /** No route from the visitor to the place with the route accessibility needs. */
        NO_ROUTE,

        /** A map without a journey has no position of the visitor yet. */
        NO_POSITION,
    }
}

/**
 * What [ProximiMap] reports to the host app, from [ProximiMap.events]. `ProximiMap` performs every
 * action itself; the events only report it. New cases can come in minor versions: a `when` with
 * `else` keeps compiling.
 */
public sealed interface ProximiMapEvent {
    // Screens

    /** A screen opened, by [ProximiMap.open] or inside a flow (the stop picker opens Journey ready). */
    public data class Opened(public val screen: ProximiMap.Screen) : ProximiMapEvent

    /** A screen closed. The host app's screen below takes taps again. */
    public data class Closed(public val screen: ProximiMap.Screen) : ProximiMapEvent

    /**
     * "Craft a new journey" on Manage journey. The host app shows its journey crafting, for example
     * the audio guide. Sent after Manage journey has closed.
     */
    public data object CraftNewJourneyRequested : ProximiMapEvent

    /**
     * "Edit stops" on Manage journey for a curated tour. The host app shows its tour choice. Sent
     * after Manage journey has closed.
     */
    public data object CuratedTourChangeRequested : ProximiMapEvent

    // Visitor and wristband

    /**
     * The visitor is at the museum: a wristband or playback position, or a phone fix within 400 m
     * of a place of the venue. Once per [ProximiMap].
     */
    public data object ArrivedAtMuseum : ProximiMapEvent

    public data class WristbandChanged(public val status: WristbandStatus) : ProximiMapEvent

    /** The wristband's positions stopped or came back. No alert is shown; the host app decides. */
    public data class TrackingChanged(public val health: TrackingHealth) : ProximiMapEvent

    /**
     * The visitor entered a geofence, for example an artwork's. Sent with or without a journey, for
     * the audio guide.
     */
    public data class GeofenceEntered(public val geofence: ProximiGeofence) : ProximiMapEvent

    /** The visitor left a geofence. */
    public data class GeofenceExited(public val geofence: ProximiGeofence) : ProximiMapEvent

    // Journey

    /** A new, edited or deleted (`null`) journey. */
    public data class JourneyChanged(public val journey: JourneySummary?) : ProximiMapEvent

    /** The route leads to this stop now. */
    public data class NextStop(public val stop: ProximiStop) : ProximiMapEvent

    /**
     * The active stop is less than [GadSettings.stopNearbyMeters] away on the visitor's level. Once
     * per stop. The library posts no notification for it.
     */
    public data class StopNearby(public val stop: ProximiStop) : ProximiMapEvent

    /**
     * The position is uncertain near the active stop: the accuracy is worse than
     * [GadSettings.uncertainAccuracyMeters] and the stop is within it. Sent by [ProximiMapView]
     * without [ProximiMapView.Option.UNCERTAIN_POSITION_PROMPT], which otherwise asks "Probably at …".
     * At most once per
     * [GadSettings.locationQuestionQuietPeriod].
     */
    public data class PositionUncertain(public val stop: ProximiStop) : ProximiMapEvent

    /**
     * The stop's POI was deleted on the backend while [ProximiMapView] is open. Once per stop. Without
     * [ProximiMapView.Option.PROGRESS] the stop stays on the screen until the host app changes the
     * journey; that change starts the screen again without it. With [ProximiMapView.Option.PROGRESS]
     * the stop is skipped.
     */
    public data class StopPlaceDeleted(public val stop: ProximiStop) : ProximiMapEvent

    /**
     * The visitor left the geofence of the active stop's artwork ([GadSettings.advancesOnArtworkExit]),
     * or [GadSettings.artworkAdvanceDelay] after they reached it without that setting: the stop is
     * done and the route leads to the next stop.
     */
    public data class ArtworkReached(public val stop: ProximiStop) : ProximiMapEvent

    /**
     * The visitor stayed at an artwork further down the journey. It counts as visited. A
     * personalised journey orders the stops ahead again.
     */
    public data class ArtworkVisitedOutOfOrder(public val stop: ProximiStop) : ProximiMapEvent

    /**
     * [ProximiMapView] with [ProximiMapView.Option.PROGRESS]: the view ordered the stops again, from
     * the visitor's first position, after [ArtworkVisitedOutOfOrder] or after a detour. The journey in
     * the host app's ids and its new order; `onJourneyChange` got it just before.
     */
    public data class StopOrderChanged(public val journey: ProximiJourney) : ProximiMapEvent

    /**
     * The visitor swiped a stop in the list. With [ProximiMapView.Option.SKIP_PROMPT] a dialog asks
     * "Skip artwork?"; without it the host app decides.
     */
    public data class SkipRequested(public val stop: ProximiStop) : ProximiMapEvent

    public data class StopSkipped(public val stop: ProximiStop) : ProximiMapEvent

    public data class SkipCancelled(public val stop: ProximiStop) : ProximiMapEvent

    /** Not sent since 1.14.0: the list has no "Undo skip" (PM, Monday "Undo skip removed", 2026-10-23). */
    @Deprecated("Not sent since 1.14.0: the list has no Undo skip.")
    public data class SkipUndone(public val stop: ProximiStop) : ProximiMapEvent

    /** The visitor picked an amenity on the map. A card shows it with "Add a stop" (during a journey) or "Go here". */
    public data class AmenitySelected(public val stop: ProximiStop) : ProximiMapEvent

    /** The amenity is now a detour of the journey. */
    public data class DetourStarted(public val stop: ProximiStop) : ProximiMapEvent

    /** The detour ended: reached ([completed]) or left. */
    public data class DetourEnded(public val stop: ProximiStop, public val completed: Boolean) : ProximiMapEvent

    /**
     * [ProximiMapView] does not route to the `destination` the host app set, and sets it back to
     * `null` through `onDestinationChange`.
     */
    public data class DestinationUnavailable(public val poiId: String, public val reason: ProximiDestination.Failure) : ProximiMapEvent

    /**
     * "Add a stop" on an amenity: the journey waits while the visitor goes there, until "Resume your
     * Journey" ([JourneyResumed]). Sent after [DetourStarted], and after `onPausedChange(true)`: the
     * host app's pause control shows the journey paused, and its resume ends the detour. A detour to
     * the host app's `destination` sends none.
     */
    public data object JourneyPaused : ProximiMapEvent

    /**
     * "Resume your Journey": after a detour, or on the card of a journey the host app paused
     * ([ProximiMapView]'s `isPaused`, which the view then sets back to `false` through
     * `onPausedChange`). The host app's own `false` reports none.
     */
    public data object JourneyResumed : ProximiMapEvent

    /** The visitor has been off the route for [GadSettings.offRouteAfter]. */
    public data class OffRoute(public val duration: Duration) : ProximiMapEvent

    /** The visitor is on the route again after [OffRoute]. */
    public data object BackOnRoute : ProximiMapEvent

    /**
     * Every stop is visited or skipped. [ProximiMap], and [ProximiMapView] with
     * [ProximiMapView.Option.JOURNEY_COMPLETE], show Journey Complete.
     */
    public data object JourneyFinished : ProximiMapEvent

    /** The map shows a floor without positioning and does not follow the visitor. */
    public data class FloorWithoutPositioningEntered(public val level: Int) : ProximiMapEvent

    /** The map follows the visitor again. */
    public data class FloorWithoutPositioningLeft(public val level: Int) : ProximiMapEvent

    // Controls on the journey screen

    /** The Map / List toggle. */
    public data class ModeChanged(public val mode: VisitMode) : ProximiMapEvent

    /** "Centre on me". */
    public data object Recentred : ProximiMapEvent

    /** The accessible routes button: on (`true`) or off. */
    public data class AccessibleRoutesChanged(public val isOn: Boolean) : ProximiMapEvent

    /** Back during a journey in progress. "Leave the tour here" asks before the journey screen closes. */
    public data object ExitRequested : ProximiMapEvent

    public data object ExitConfirmed : ProximiMapEvent

    public data object ExitCancelled : ProximiMapEvent
}

/** An error of [ProximiMap]. [message] is the text for the visitor, in the app's language. */
public sealed class ProximiMapException(message: String) : Exception(message) {
    /** [ProximiMap.start] has not finished. */
    public class NotStarted internal constructor(message: String) : ProximiMapException(message)

    /**
     * The SDK did not start, for example without network on the first launch. [ProximiMap.start]
     * can be called again. [reason] is the underlying error, for logs.
     */
    public class StartFailed internal constructor(message: String, public val reason: String) : ProximiMapException(message)

    /**
     * The wristband was not connected, or the session not ended. [message] says why, for example
     * "This wristband is already in use. Please ask the staff."
     */
    public class Wristband internal constructor(message: String) : ProximiMapException(message)

    /** None of the plan's artworks is a POI of the venue. */
    public class NoKnownArtworks internal constructor(message: String) : ProximiMapException(message)
}
