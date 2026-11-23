package io.proximi.gadkit.visit

import io.proximi.gadkit.GadSettings
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
        // A paused journey: the text of the notice replaces the hint (iOS 1.59.1).
        assertEquals(R.string.resume_your_journey_to_skip_the_artwork, SkipSwipe.hint(isRtl = false, isSkipPaused = true))
        assertEquals(R.string.resume_your_journey_to_skip_the_artwork, SkipSwipe.hint(isRtl = true, isSkipPaused = true))
        assertFalse(GadSettings().skipsWhilePaused)
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

    /** iOS 1.71.2: the skip reads the stop again from the journey; a skipped or visited stop is refused. */
    @Test
    fun theSkipTakesTheStopAsTheJourneyHoldsItNow() {
        fun stop(id: String, state: StopState) = JourneyStop(id = id, title = "Artwork $id", coordinate = Coordinate(24.5, 54.4), level = 1.0, poiId = id, state = state)
        val stops = listOf(stop("a", StopState.DONE), stop("b", StopState.ACTIVE), stop("c", StopState.SKIPPED), stop("d", StopState.PENDING))
        assertEquals("d", SkipSwipe.stopToSkip(stops, "d")?.id)
        assertEquals(StopState.ACTIVE, SkipSwipe.stopToSkip(stops, "b")?.state)
        assertEquals(null, SkipSwipe.stopToSkip(stops, "c"))
        assertEquals(null, SkipSwipe.stopToSkip(stops, "a"))
        assertEquals(null, SkipSwipe.stopToSkip(stops, "x"))
    }

    @Test
    fun aSwipeAsksForTheSkipAtOnceWhenSet() {
        val revealing = SkipSwipe.end(offset = -60f, velocity = 0f, revealWidth = revealWidth, skipsAtSwipe = true)
        assertTrue(!revealing.isRevealed && revealing.skips)
        val short = SkipSwipe.end(offset = -20f, velocity = 0f, revealWidth = revealWidth, skipsAtSwipe = true)
        assertTrue(!short.isRevealed && !short.skips)
        val button = SkipSwipe.end(offset = -60f, velocity = 0f, revealWidth = revealWidth, skipsAtSwipe = false)
        assertTrue(button.isRevealed && !button.skips)
    }

    /**
     * With both ways a swipe towards the end edge asks for the skip; without it, and without
     * `skipsAtSwipe`, it does nothing (iOS 1.71.0).
     */
    @Test
    fun aSwipeTowardsTheEndEdgeAsksForTheSkipWhenSet() {
        val trailing = SkipSwipe.end(offset = 103f, velocity = 0f, revealWidth = revealWidth, skipsAtSwipe = true, bothWays = true)
        assertTrue(!trailing.isRevealed && trailing.skips)
        val short = SkipSwipe.end(offset = 30f, velocity = 0f, revealWidth = revealWidth, skipsAtSwipe = true, bothWays = true)
        assertTrue(!short.isRevealed && !short.skips)
        val oneWay = SkipSwipe.end(offset = 103f, velocity = 0f, revealWidth = revealWidth, skipsAtSwipe = true, bothWays = false)
        assertTrue(!oneWay.isRevealed && !oneWay.skips)
        val button = SkipSwipe.end(offset = 103f, velocity = 0f, revealWidth = revealWidth, skipsAtSwipe = false, bothWays = true)
        assertTrue(!button.isRevealed && !button.skips)
        // The row follows the finger towards the end edge, then it is damped.
        assertEquals(50f, SkipSwipe.rubberBand(50f, revealWidth, bothWays = true))
        assertEquals(102f, SkipSwipe.rubberBand(172f, revealWidth, bothWays = true))
        assertEquals(10f, SkipSwipe.rubberBand(50f, revealWidth))
        assertTrue(GadSettings().skipsAtSwipeBothWays)
    }
}
