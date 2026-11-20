package io.proximi.gadkit.visit

import io.proximi.map.core.JourneyEvent

/**
 * Holds the navigator's `LegChanged` and `JourneyFinished` while the controller marks an artwork
 * visited and orders the stops ahead again (`VisitController.holdsLegEvents`). iOS `LegEventGate`
 * (1.61.0; Metis T-1870, T-1871).
 *
 * The navigator sends `LegChanged` before the controller reports `ArtworkVisited`. Marking a stop
 * done and ordering the rest are two navigator calls and give two `LegChanged`: the first names the
 * next stop of the old order.
 *
 * The gate hands on one `LegChanged`, the last one, after the controller is done. It drops that
 * event when it names the stop of the last `LegChanged` handed on: the stop walked to did not change
 * (an artwork visited out of order).
 *
 * Pure; covered by tests.
 */
internal class LegEventGate {
    private var heldLeg: JourneyEvent.LegChanged? = null
    private var heldFinish = false

    /** The stop of the last `LegChanged` handed on. */
    var lastLegStopId: String? = null
        private set

    /** The events to hand on for [event], in order. */
    fun receive(event: JourneyEvent, isHolding: Boolean): List<JourneyEvent> = when {
        event is JourneyEvent.LegChanged -> if (isHolding) {
            heldLeg = event
            emptyList()
        } else {
            lastLegStopId = event.to.id
            listOf(event)
        }
        event == JourneyEvent.JourneyFinished -> if (isHolding) {
            heldFinish = true
            emptyList()
        } else {
            listOf(event)
        }
        else -> listOf(event)
    }

    /** The held events to hand on once the controller is done, in order. */
    fun release(): List<JourneyEvent> {
        val events = mutableListOf<JourneyEvent>()
        val leg = heldLeg
        if (leg != null && !heldFinish && leg.to.id != lastLegStopId) {
            lastLegStopId = leg.to.id
            events += leg
        }
        if (heldFinish) events += JourneyEvent.JourneyFinished
        heldLeg = null
        heldFinish = false
        return events
    }

    /** Whether the gate holds no event. */
    val isEmpty: Boolean get() = heldLeg == null && !heldFinish
}
