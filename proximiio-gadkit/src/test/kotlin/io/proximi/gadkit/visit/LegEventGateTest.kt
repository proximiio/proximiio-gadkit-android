package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.map.core.JourneyEvent
import io.proximi.map.core.JourneyLegRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [LegEventGate]: `ArtworkVisited` before `NextStop`, one `NextStop` per visit (Metis T-1870, T-1871). */
class LegEventGateTest {
    private fun leg(id: String): JourneyEvent = JourneyEvent.LegChanged(
        to = JourneyStop(id = id, title = id, coordinate = Coordinate(24.54, 54.39), level = 1.0, poiId = id).toMap(),
        reason = JourneyLegRequest.Reason.STARTED,
    )

    private fun legIds(events: List<JourneyEvent>): List<String> = events.map { event ->
        when {
            event is JourneyEvent.LegChanged -> event.to.id
            event == JourneyEvent.JourneyFinished -> "finished"
            else -> "other"
        }
    }

    @Test
    fun aLegChangeOutsideAVisitIsHandedOnAtOnce() {
        val gate = LegEventGate()
        assertEquals(listOf("a"), legIds(gate.receive(leg("a"), isHolding = false)))
        assertEquals("a", gate.lastLegStopId)
        assertTrue(gate.release().isEmpty())
    }

    @Test
    fun aVisitGivesOneLegChangeForTheStopOfTheNewOrder() {
        val gate = LegEventGate()
        gate.receive(leg("a"), isHolding = false)
        // Marked done: the next stop of the old order. Ordered again: the stop of the new order.
        assertTrue(gate.receive(leg("b"), isHolding = true).isEmpty())
        assertTrue(gate.receive(leg("c"), isHolding = true).isEmpty())
        assertEquals(listOf("c"), legIds(gate.release()))
        assertEquals("c", gate.lastLegStopId)
        assertTrue(gate.release().isEmpty())
    }

    @Test
    fun aVisitOutOfOrderThatKeepsTheStopGivesNoLegChange() {
        val gate = LegEventGate()
        gate.receive(leg("a"), isHolding = false)
        assertTrue(gate.receive(leg("a"), isHolding = true).isEmpty())
        assertTrue(gate.release().isEmpty())
        assertTrue(gate.isEmpty)
    }

    @Test
    fun theEndOfTheJourneyIsHeldAndNoLegChangeGoesBeforeIt() {
        val gate = LegEventGate()
        gate.receive(leg("a"), isHolding = false)
        assertTrue(gate.receive(leg("b"), isHolding = true).isEmpty())
        assertTrue(gate.receive(JourneyEvent.JourneyFinished, isHolding = true).isEmpty())
        assertEquals(listOf("finished"), legIds(gate.release()))
    }

    @Test
    fun otherEventsAreNeverHeld() {
        val gate = LegEventGate()
        assertEquals(1, gate.receive(JourneyEvent.ReturnedToRoute, isHolding = true).size)
        assertTrue(gate.isEmpty)
    }
}
