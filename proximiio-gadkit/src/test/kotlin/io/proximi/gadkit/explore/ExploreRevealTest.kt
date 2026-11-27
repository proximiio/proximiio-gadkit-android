package io.proximi.gadkit.explore

import io.proximi.gadkit.GadSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** iOS `ExploreRevealTests` (1.76.0): when the hidden Explore map shows. */
class ExploreRevealTest {
    private fun reason(elapsed: Double, isIdle: Boolean = false, wasIdle: Boolean = false, hasError: Boolean = false, maximum: Double = 4.0) =
        ExploreReveal.reason(elapsed, isIdle, wasIdleAtFrame = wasIdle, hasError = hasError, maximumSeconds = maximum)

    @Test
    fun theMapWaitsForTheIdleMap() {
        assertNull(reason(0.1))
        assertNull(reason(3.9))
        assertEquals("venue drawn", reason(0.1, isIdle = true))
    }

    @Test
    fun theMapShowsAfterTheLongestWait() {
        assertEquals("after the longest wait", reason(4.0))
        assertEquals("after the longest wait", reason(1.0, maximum = 1.0))
    }

    @Test
    fun aMapIdleBeforeTheCameraGetsTheSettleTime() {
        assertNull(reason(0.2, isIdle = true, wasIdle = true))
        assertEquals("drawn before the camera was set", reason(0.5, isIdle = true, wasIdle = true))
    }

    @Test
    fun aMapErrorShowsTheMap() {
        assertEquals("map error", reason(0.0, hasError = true))
    }

    @Test
    fun theDefaultWaitIsFourSeconds() {
        assertEquals(4.seconds, GadSettings.STANDARD.exploreMapRevealMaximumSeconds)
    }
}
