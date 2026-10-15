package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.StopKind
import io.proximi.gadkit.visit.StopState
import io.proximi.map.core.FloorKey
import io.proximi.map.core.MapCoordinate
import io.proximi.map.core.Journey as MapJourney
import io.proximi.map.core.JourneyStop as MapJourneyStop

// Core's journey mirrors the map library's; these convert between the two.

internal fun MapJourney.toCore(): Journey = Journey(stops.map { it.toCore() })

internal fun Journey.toMap(): MapJourney = MapJourney(stops.map { it.toMap() })

internal fun MapJourneyStop.toCore(): JourneyStop = JourneyStop(
    id = id,
    title = title,
    coordinate = Coordinate(coordinate.latitude, coordinate.longitude),
    level = floor?.level,
    poiId = poiId,
    state = when (state) {
        MapJourneyStop.State.PENDING -> StopState.PENDING
        MapJourneyStop.State.ACTIVE -> StopState.ACTIVE
        MapJourneyStop.State.REACHED -> StopState.REACHED
        MapJourneyStop.State.DONE -> StopState.DONE
        MapJourneyStop.State.SKIPPED -> StopState.SKIPPED
    },
    kind = if (kind == MapJourneyStop.Kind.DETOUR) StopKind.DETOUR else StopKind.PLANNED,
    group = group,
)

internal fun JourneyStop.toMap(): MapJourneyStop = MapJourneyStop(
    id = id,
    title = title,
    coordinate = MapCoordinate(coordinate.latitude, coordinate.longitude),
    floor = FloorKey.of(level),
    poiId = poiId,
    kind = if (kind == StopKind.DETOUR) MapJourneyStop.Kind.DETOUR else MapJourneyStop.Kind.PLANNED,
    group = group,
    state = when (state) {
        StopState.PENDING -> MapJourneyStop.State.PENDING
        StopState.ACTIVE -> MapJourneyStop.State.ACTIVE
        StopState.REACHED -> MapJourneyStop.State.REACHED
        StopState.DONE -> MapJourneyStop.State.DONE
        StopState.SKIPPED -> MapJourneyStop.State.SKIPPED
    },
)
