package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.Geometry
import io.proximi.gadkit.map.VenueFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of iOS `LevelChangeTests`. */
class LevelChangeTest {
    @Test
    fun firstFloorIsNoChange() {
        val detector = LevelChangeDetector()
        assertNull(detector.update(1.0))
        assertEquals(1, detector.level)
    }

    @Test
    fun defaultChangesOnTheFirstFixOnTheNewFloor() {
        val detector = LevelChangeDetector()
        detector.update(1.0)
        assertEquals(LevelChange(1, 2), detector.update(2.0))
        assertNull(detector.update(2.0))
    }

    @Test
    fun changeNeedsTwoFixesOnTheNewFloor() {
        val detector = LevelChangeDetector(confirmations = 2)
        detector.update(1.0)
        assertNull(detector.update(2.0))
        assertEquals(LevelChange(1, 2), detector.update(2.0))
        assertNull(detector.update(2.0))
    }

    @Test
    fun singleFixOnAnotherFloorIsIgnored() {
        val detector = LevelChangeDetector(confirmations = 2)
        detector.update(1.0)
        assertNull(detector.update(2.0))
        assertNull(detector.update(1.0))
        assertNull(detector.update(2.0))
        assertEquals(1, detector.level)
    }

    @Test
    fun directionAndMissingFloor() {
        val detector = LevelChangeDetector()
        detector.update(4.0)
        assertNull(detector.update(null))
        val change = detector.update(2.0001)
        assertEquals(LevelChange(4, 2), change)
        assertFalse(change!!.isUp)
    }

    // Lift

    /** Field log 2026-10-08 16:03:39–16:03:50: 1 s fixes inside the lift on L1, then L2. */
    @Test
    fun standingInTheLiftBoardsAfterTheDwell() {
        val lift = LiftDetector()
        assertNull(lift.update(false, 0))
        assertNull(lift.update(true, 1_000))
        assertNull(lift.update(true, 2_000))
        assertNull(lift.update(true, 3_000))
        assertEquals(LiftDetector.Event.BOARDED, lift.update(true, 4_000))
        assertNull(lift.update(true, 5_000))
        assertTrue(lift.isRiding)
    }

    /** Field log 16:03:54–16:03:55: two fixes inside another lift while walking on L2. */
    @Test
    fun walkingThroughALiftIsNoRide() {
        val lift = LiftDetector()
        assertNull(lift.update(true, 0))
        assertNull(lift.update(true, 1_000))
        assertNull(lift.update(false, 2_000))
        assertNull(lift.update(false, 3_000))
        assertNull(lift.update(true, 4_000))
        assertFalse(lift.isRiding)
    }

    @Test
    fun leavingTheLiftEndsTheRideAfterTwoFixesOutside() {
        val lift = LiftDetector()
        lift.update(true, 0)
        assertEquals(LiftDetector.Event.BOARDED, lift.update(true, 3_000))
        assertNull(lift.update(false, 4_000))
        assertNull(lift.update(true, 5_000))
        assertNull(lift.update(false, 6_000))
        assertEquals(LiftDetector.Event.LEFT, lift.update(false, 7_000))
        assertFalse(lift.isRiding)
    }

    @Test
    fun liftAreasAreElevatorOutlinesOnEveryFloor() {
        val square = listOf(listOf(listOf(0.0, 0.0), listOf(1.0, 0.0), listOf(1.0, 1.0), listOf(0.0, 1.0), listOf(0.0, 0.0)))
        val features = listOf(
            VenueFeature("lift", "elevator", Geometry("Polygon", square), null, 1.0),
            VenueFeature("room", "gallery", Geometry("Polygon", square), null, 1.0),
        )
        val lifts = LiftArea.all(features)
        assertEquals(1, lifts.size)
        assertTrue(lifts[0].contains(Coordinate(0.5, 0.5)))
        assertFalse(lifts[0].contains(Coordinate(2.0, 0.5)))
    }

    @Test
    fun routeTargetIsALevelChangeFromTheCurrentFloor() {
        assertEquals(2, LevelChangeCard.routeTarget(1, 1.0, 2.0))
        assertNull(LevelChangeCard.routeTarget(3, 1.0, 2.0))
        assertNull(LevelChangeCard.routeTarget(1, null, null))
    }
}
