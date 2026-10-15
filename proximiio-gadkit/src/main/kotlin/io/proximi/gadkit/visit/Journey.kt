package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate

/** State of a stop. Mirrors `JourneyStop.State` of the Proximi.io map SDK. */
public enum class StopState { PENDING, ACTIVE, REACHED, DONE, SKIPPED }

/** Planned stops come from the plan; a detour is inserted by the visitor. Mirrors `JourneyStop.Kind`. */
public enum class StopKind { PLANNED, DETOUR }

/** One stop of a [Journey]. The app maps it to and from the SDK's `JourneyStop`. */
public data class JourneyStop(
    val id: String,
    val title: String,
    val coordinate: Coordinate,
    /** Floor level, or `null` when unknown. iOS `floor?.level`. */
    val level: Double?,
    val poiId: String?,
    val state: StopState = StopState.PENDING,
    val kind: StopKind = StopKind.PLANNED,
    /**
     * The id of the gallery outline that contains the stop on its level, for the floor-then-gallery
     * order. `null` outside every outline.
     */
    val group: String? = null,
) {
    /** Pending, active or reached: still to visit. */
    val isOpen: Boolean get() = state == StopState.PENDING || state == StopState.ACTIVE || state == StopState.REACHED
}

/** The ordered stops of a visit. */
public data class Journey(val stops: List<JourneyStop>)
