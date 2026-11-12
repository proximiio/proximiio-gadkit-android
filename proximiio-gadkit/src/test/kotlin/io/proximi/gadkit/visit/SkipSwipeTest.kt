package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of ProximiioGADKit `SkipSwipeTests` (iOS 1.37.1; PM, Monday "Skipping in Arabic goes the wrong
 * way" and "When all artworks skipped, remove 'skip artwork' text"). Offsets and velocities are
 * logical: `draggable` with `reverseDirection` already turns a right-to-left drag into one.
 */
class SkipSwipeTest {
    private val revealWidth = 72f

    @Test
    fun aDragTowardsTheStartEdgeRevealsThePanel() {
        assertTrue(SkipSwipe.reveals(offset = -60f, velocity = 0f, revealWidth = revealWidth))
        assertFalse(SkipSwipe.reveals(offset = 60f, velocity = 0f, revealWidth = revealWidth))
        // A quick swipe: a short drag with a velocity towards the start edge.
        assertTrue(SkipSwipe.reveals(offset = -10f, velocity = -400f, revealWidth = revealWidth))
        assertFalse(SkipSwipe.reveals(offset = -30f, velocity = 400f, revealWidth = revealWidth))
    }

    @Test
    fun aDragBackClosesARevealedPanel() {
        assertFalse(SkipSwipe.reveals(offset = -revealWidth + 60f, velocity = 0f, revealWidth = revealWidth))
        assertTrue(SkipSwipe.reveals(offset = -revealWidth + 10f, velocity = 0f, revealWidth = revealWidth))
    }

    @Test
    fun theRowRubberBandsPastBothEnds() {
        assertEquals(10f, SkipSwipe.rubberBand(50f, revealWidth))
        assertEquals(-102f, SkipSwipe.rubberBand(-172f, revealWidth))
        assertEquals(-40f, SkipSwipe.rubberBand(-40f, revealWidth))
    }

    @Test
    fun theHintNamesTheDirectionOfTheLayout() {
        assertEquals(R.string.swipe_left_to_skip_an_artwork, SkipSwipe.hint(isRtl = false))
        assertEquals(R.string.swipe_right_to_skip_an_artwork, SkipSwipe.hint(isRtl = true))
    }

    @Test
    fun onlyAStopStillAheadCanBeSkipped() {
        fun stop(state: StopState) = JourneyStop(id = "a", title = "Artwork 1", coordinate = Coordinate(24.5, 54.4), level = 1.0, poiId = "a", state = state)
        assertTrue(SkipSwipe.canSkip(stop(StopState.PENDING)))
        assertTrue(SkipSwipe.canSkip(stop(StopState.ACTIVE)))
        assertFalse(SkipSwipe.canSkip(stop(StopState.SKIPPED)))
        assertFalse(SkipSwipe.canSkip(stop(StopState.DONE)))
        assertFalse(SkipSwipe.canSkip(stop(StopState.REACHED)))
    }
}
