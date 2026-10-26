package io.proximi.gadkit.visit

import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.wristband.TrackingHealth
import io.proximi.map.core.JourneyEvent

/** The two presentations of the journey in `VisitScreen`. */
public enum class VisitMode { MAP, LIST }

/**
 * A visitor action in `VisitScreen`, or a change of the journey, as iOS `VisitEvent`.
 * `ProximiMapHost` passes them to `ProximiMap`, which sends them to the host app as
 * `ProximiMapEvent`. `VisitScreen` performs every action itself; the events only report them.
 */
internal sealed interface VisitEvent {
    /** The presentation changed: the Map / List toggle. */
    data class ModeChanged(val mode: VisitMode) : VisitEvent

    /** The visitor swiped a stop in the list, or used its "Skip" accessibility action. `VisitScreen` asks "Skip artwork?". */
    data class SkipRequested(val stop: JourneyStop) : VisitEvent

    /**
     * The visitor confirmed "Skip artwork". With [io.proximi.gadkit.ProximiMapView.Option.PROGRESS]
     * the stop is skipped; without it the host app skips it.
     */
    data class StopSkipped(val stop: JourneyStop) : VisitEvent

    /** The visitor answered "Keep it". The stop stays in the journey. */
    data class SkipCancelled(val stop: JourneyStop) : VisitEvent

    /** The visitor picked an amenity on the map. A card shows it with "Go here". */
    data class AmenitySelected(val place: VenuePlace) : VisitEvent

    /** The amenity is now a detour of the journey: the visitor confirmed "Change your route?". */
    data class DetourStarted(val place: VenuePlace) : VisitEvent

    /** The visitor tapped "Resume your Journey" or "Show the route from here". */
    data object JourneyResumed : VisitEvent

    /** The visitor tapped "Centre on me". */
    data object Recentred : VisitEvent

    /**
     * The visitor turned the accessible routes button on (`true`) or off. Routes avoid stairs,
     * escalators, revolving doors and inaccessible paths while it is on.
     */
    data class AccessibleRoutesChanged(val isOn: Boolean) : VisitEvent

    /** The visitor tapped Back during a visit in progress. `VisitScreen` asks before it closes. */
    data object ExitRequested : VisitEvent

    /** The visitor confirmed leaving the visit. `onClose` follows. */
    data object ExitConfirmed : VisitEvent

    /** The visitor answered "Carry on". The visit continues. */
    data object ExitCancelled : VisitEvent

    /**
     * The active stop is less than [io.proximi.gadkit.GadSettings.stopNearbyMeters] away on the
     * visitor's level. Once per stop for the whole visit ([StopNearbyDetector]).
     */
    data class StopNearby(val stop: JourneyStop) : VisitEvent

    /**
     * The POI of a stop was deleted on the backend while the screen is open. Once per stop. With
     * [io.proximi.gadkit.ProximiMapView.Option.PROGRESS] the stop is skipped; without it the stop
     * stays and the host app decides.
     */
    data class StopPlaceDeleted(val stop: JourneyStop) : VisitEvent

    /**
     * The position is uncertain near the active stop ("Probably at …"). Without
     * [io.proximi.gadkit.ProximiMapView.Option.PROMPTS] the screen reports it instead of asking. At
     * most once per [io.proximi.gadkit.GadSettings.locationQuestionQuietPeriod].
     */
    data class PositionUncertain(val stop: JourneyStop) : VisitEvent

    /**
     * The visitor reached the artwork of the active stop: they entered its geofence or stand in its
     * area. The stop is done 2 s later, when this event arrives, and the route leads to the next
     * stop. The navigator reports no `StopReached` for artworks.
     */
    data class ArtworkReached(val stop: JourneyStop) : VisitEvent

    /**
     * The visitor stayed at an artwork further down the journey. The stop is done and the stops
     * still ahead are ordered again from the visitor.
     */
    data class ArtworkVisitedOutOfOrder(val stop: JourneyStop) : VisitEvent

    /**
     * The wristband's positions stopped or came back ([TrackingHealth]). `VisitScreen` shows no
     * alert: the host app handles the wristband states (PM 2026-10-15).
     */
    data class TrackingChanged(val health: TrackingHealth) : VisitEvent

    /**
     * The map shows a floor without positioning (`GadSettings.levelsWithoutPositioning`) after the
     * lift or stairs view, and does not follow the visitor.
     */
    data class FloorWithoutPositioningEntered(val level: Int) : VisitEvent

    /** The map follows the visitor again after a floor without positioning. */
    data class FloorWithoutPositioningLeft(val level: Int) : VisitEvent

    /**
     * An event of the journey navigator: `LegChanged`, `StopSkipped`, `Rerouted`, `DetourStarted`,
     * `StopReached` (detours only), `JourneyFinished` and others. For artworks use [ArtworkReached]:
     * the navigator does not report them reached.
     */
    data class Journey(val event: JourneyEvent) : VisitEvent
}
