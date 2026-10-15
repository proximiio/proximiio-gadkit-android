package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.UiText
import io.proximi.gadkit.map.VenuePlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of guggenheim-ios `VisitLiveActivityTests` (in VisitPlanTests.swift), plus the update throttle. */
class VisitActivityTest {
    private val places = listOf(
        VenuePlace(id = "a", title = "Gallery 101", coordinate = Coordinate(0.0, 0.0), level = 1.0),
        VenuePlace(id = "b", title = "Gallery 304", coordinate = Coordinate(0.0, 0.0), level = 3.0),
    )

    @Test
    fun statesForEachCard() {
        val journey = VisitPlan.journey(listOf("a", "b"), places)
        val turn = UiText.Resource("guidance_turn_left", listOf(50))
        val walking = VisitActivity.contentState(VisitCardState.Walking("Gallery 101"), journey, "a", turn)
        assertEquals(
            VisitActivityState(UiText.Verbatim("Gallery 101"), UiText.Resource("visit_stop_n_of_n", listOf(1, 2)), turn, "", false),
            walking,
        )
        val level = VisitActivity.contentState(VisitCardState.NextStopOnOtherLevel("Gallery 304", 3), journey, "b", null)
        assertEquals("L3", level.level)
        assertEquals(UiText.Resource("visit_stop_n_of_n", listOf(2, 2)), level.progress)
        val finished = VisitActivity.contentState(VisitCardState.Finished, journey, null, null)
        assertTrue(finished.isFinished)
        assertEquals(UiText.Resource("visit_journey_complete"), finished.title)
        assertEquals(UiText.Resource("visit_n_stops", listOf(2)), finished.progress)
    }

    @Test
    fun startingAndAtStop() {
        val journey = VisitPlan.journey(listOf("a", "b"), places)
        val starting = VisitActivity.contentState(VisitCardState.Starting, journey, null, null)
        assertEquals(UiText.Resource("visit_your_journey"), starting.title)
        assertEquals(UiText.Resource("visit_n_stops", listOf(2)), starting.progress)
        val atStop = VisitActivity.contentState(VisitCardState.AtStop("Gallery 101"), journey, "a", null)
        assertEquals(UiText.Resource("guidance_arrived"), atStop.instruction)
    }

    @Test
    fun stepChangesGoAtOnceAndInstructionChangesAreThrottled() {
        val first = VisitActivityState(UiText.Verbatim("A"), UiText.Verbatim("1"), UiText.Verbatim("left"), "", false)
        assertTrue(VisitActivity.shouldSend(first, null, 0.0))
        assertFalse(VisitActivity.shouldSend(first, first, 60.0))
        val newInstruction = first.copy(instruction = UiText.Verbatim("right"))
        assertFalse(VisitActivity.shouldSend(newInstruction, first, 2.0))
        assertTrue(VisitActivity.shouldSend(newInstruction, first, 5.0))
        assertTrue(VisitActivity.shouldSend(first.copy(title = UiText.Verbatim("B")), first, 0.1))
        assertTrue(VisitActivity.shouldSend(first.copy(level = "L2"), first, 0.1))
        assertTrue(VisitActivity.shouldSend(first.copy(isFinished = true), first, 0.1))
    }
}
