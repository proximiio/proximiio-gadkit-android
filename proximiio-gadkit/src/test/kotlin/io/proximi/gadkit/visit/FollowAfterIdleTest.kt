package io.proximi.gadkit.visit

import io.proximi.gadkit.GadSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** iOS `FollowAfterIdleTests` (1.75.0): a still, free camera of the journey map follows the visitor again. */
class FollowAfterIdleTest {
    private val here = FollowAfterIdle.Camera.of(24.5398, 54.3992, zoom = 19.0, direction = 0.0)
    private val moved = FollowAfterIdle.Camera.of(24.5399, 54.3992, zoom = 19.0, direction = 0.0)

    private fun FollowAfterIdle.read(camera: FollowAfterIdle.Camera?, now: Double, isFree: Boolean = true, isAllowed: Boolean = true, seconds: Double = 5.0) =
        reading(camera, isFree, isAllowed, seconds, now)

    @Test
    fun aFreeCameraFollowsAgainAfterTheSecondsWithoutAMove() {
        val idle = FollowAfterIdle()
        assertFalse(idle.read(here, 100.0))
        assertFalse(idle.read(here, 104.5))
        assertTrue(idle.read(here, 105.0))
        // The next follow needs a new still time.
        assertFalse(idle.read(here, 105.5))
    }

    @Test
    fun aMoveOfTheCameraStartsTheTimeAgain() {
        val idle = FollowAfterIdle()
        assertFalse(idle.read(here, 100.0))
        assertFalse(idle.read(moved, 104.0))
        assertFalse(idle.read(moved, 108.5))
        assertTrue(idle.read(moved, 109.0))
    }

    @Test
    fun zeroSecondsNeverFollows() {
        val idle = FollowAfterIdle()
        assertFalse(idle.read(here, 100.0, seconds = 0.0))
        assertFalse(idle.read(here, 1000.0, seconds = 0.0))
    }

    @Test
    fun aFollowingCameraAndAConditionStartTheTimeAgain() {
        val idle = FollowAfterIdle()
        assertFalse(idle.read(here, 100.0))
        assertFalse(idle.read(here, 110.0, isFree = false))
        assertFalse(idle.read(here, 111.0))
        assertFalse(idle.read(here, 120.0, isAllowed = false))
        assertFalse(idle.read(here, 121.0))
        assertTrue(idle.read(here, 126.0))
    }

    @Test
    fun aCameraWithoutAReadingDoesNotFollow() {
        val idle = FollowAfterIdle()
        assertFalse(idle.read(null, 100.0))
        assertFalse(idle.read(null, 110.0))
    }

    @Test
    fun theFollowNeedsTheVisitorsFloorAndNoOtherView() {
        fun allowed(isPaused: Boolean = false, guide: Boolean = false, start: Boolean = false, place: Boolean = false, visitor: Double? = 1.0, shown: Double = 1.0) =
            VisitViewModel.followsAgainAfterIdle(isPaused, isFloorGuideActive = guide, isStartZoomPending = start, showsPlace = place, visitorLevel = visitor, shownLevel = shown)
        assertTrue(allowed())
        assertFalse(allowed(isPaused = true))
        assertFalse(allowed(guide = true))
        assertFalse(allowed(start = true))
        assertFalse(allowed(place = true))
        assertFalse(allowed(visitor = null))
        assertFalse(allowed(shown = 0.0))
    }

    @Test
    fun theDefaultIsFiveSeconds() {
        assertEquals(5.seconds, GadSettings.STANDARD.followsAgainAfterSeconds)
    }
}
