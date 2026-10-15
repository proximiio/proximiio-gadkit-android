package io.proximi.gadkit.visit

import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.wristband.TrackingHealth
import io.proximi.map.core.JourneyEvent

/** The two presentations of the journey in `VisitScreen`. */
public enum class VisitMode { MAP, LIST }

/**
 * A visitor action in `VisitScreen`, or a change of the journey, as iOS `VisitEvent`. The host app
 * receives the events through the `onVisitEvent` parameter of `VisitScreen`, on the main thread.
 * `VisitScreen` performs every action itself; the events only report them.
 */
public sealed interface VisitEvent {
    /** The presentation changed: the Map / List toggle. */
    public data class ModeChanged(val mode: VisitMode) : VisitEvent

    /** The visitor swiped a stop in the list, or used its "Skip" accessibility action. `VisitScreen` asks "Skip artwork?". */
    public data class SkipRequested(val stop: JourneyStop) : VisitEvent

    /** The visitor confirmed "Skip artwork". The stop is skipped. */
    public data class StopSkipped(val stop: JourneyStop) : VisitEvent

    /** The visitor answered "Keep it". The stop stays in the journey. */
    public data class SkipCancelled(val stop: JourneyStop) : VisitEvent

    /** The visitor tapped "Undo skip". The stop is back in the journey. */
    public data class SkipUndone(val stop: JourneyStop) : VisitEvent

    /** The visitor picked an amenity on the map. A card shows it with "Go here". */
    public data class AmenitySelected(val place: VenuePlace) : VisitEvent

    /** The amenity is now a detour of the journey: the visitor confirmed "Change your route?". */
    public data class DetourStarted(val place: VenuePlace) : VisitEvent

    /** The visitor tapped "Resume your Journey" or "Show the route from here". */
    public data object JourneyResumed : VisitEvent

    /** The visitor tapped "Centre on me". */
    public data object Recentred : VisitEvent

    /**
     * The visitor turned the accessible routes button on (`true`) or off. Routes avoid stairs,
     * escalators, revolving doors and inaccessible paths while it is on.
     */
    public data class AccessibleRoutesChanged(val isOn: Boolean) : VisitEvent

    /** The visitor tapped Back during a visit in progress. `VisitScreen` asks before it closes. */
    public data object ExitRequested : VisitEvent

    /** The visitor confirmed leaving the visit. `onClose` follows. */
    public data object ExitConfirmed : VisitEvent

    /** The visitor answered "Carry on". The visit continues. */
    public data object ExitCancelled : VisitEvent

    /** The active stop is less than 5 m away on the visitor's level. Once per stop for the whole visit. */
    public data class StopNearby(val stop: JourneyStop) : VisitEvent

    /**
     * The visitor reached the artwork of the active stop: they entered its geofence or stand in its
     * area. The stop is done 2 s later, when this event arrives, and the route leads to the next
     * stop. The navigator reports no `StopReached` for artworks.
     */
    public data class ArtworkReached(val stop: JourneyStop) : VisitEvent

    /**
     * The visitor stayed at an artwork further down the journey. The stop is done and the stops
     * still ahead are ordered again from the visitor.
     */
    public data class ArtworkVisitedOutOfOrder(val stop: JourneyStop) : VisitEvent

    /**
     * The wristband's positions stopped or came back ([TrackingHealth]). `VisitScreen` shows no
     * alert: the host app handles the wristband states (PM 2026-10-15).
     */
    public data class TrackingChanged(val health: TrackingHealth) : VisitEvent

    /**
     * An event of the journey navigator: `LegChanged`, `StopSkipped`, `Rerouted`, `DetourStarted`,
     * `StopReached` (detours only), `JourneyFinished` and others. For artworks use [ArtworkReached]:
     * the navigator does not report them reached.
     */
    public data class Journey(val event: JourneyEvent) : VisitEvent
}
