package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.GadSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** Port of ProximiioGADKit `FloorWithoutPositioningTests` (0.14.0). */
class FloorWithoutPositioningTest {
    private companion object {
        /** The "Staircase" POI on GAD level 1; the route to the café on level 0 takes it. */
        val STAIRS = Coordinate(24.539731557560017, 54.39908686117707)

        /** 0.00001° of latitude is about 1.1 m. */
        fun north(metres: Double) = Coordinate(STAIRS.latitude + metres / 111_000, STAIRS.longitude)

        fun marker(type: String, from: Double, to: Double, at: Coordinate = STAIRS, completed: Boolean = false) =
            LevelChangeMarker(type, fromLevel = from, toLevel = to, isCompleted = completed, coordinate = at)
    }

    private val toCafe = listOf(marker("staircase", 1.0, 0.0))
    private val standard = GadSettings.STANDARD
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cancelTimers() = scope.cancel()

    @Test
    fun theRideToAFloorWithoutPositioningIsReachedNearTheStairs() {
        val change = FloorWithoutPositioningGuide.reachedChange(north(3.0), 1.0, toCafe, standard) ?: fail("no change")
        assertEquals(0, change.level)
        assertEquals("staircase", change.featureType)
        assertNull(FloorWithoutPositioningGuide.reachedChange(north(8.0), 1.0, toCafe, standard))
        assertNull(FloorWithoutPositioningGuide.reachedChange(north(3.0), 2.0, toCafe, standard))
        assertNull(FloorWithoutPositioningGuide.reachedChange(north(3.0), null, toCafe, standard))
    }

    @Test
    fun aFloorWithPositioningIsNoChange() {
        val toLevel4 = listOf(marker("staircase", 1.0, 3.0), marker("staircase", 3.0, 4.0))
        assertNull(FloorWithoutPositioningGuide.reachedChange(north(1.0), 1.0, toLevel4, standard))
        val none = GadSettings(levelsWithoutPositioning = emptySet())
        assertNull(FloorWithoutPositioningGuide.reachedChange(north(1.0), 1.0, toCafe, none))
        val level4 = GadSettings(levelsWithoutPositioning = setOf(4), levelChangerReachedMeters = 2.0)
        assertEquals(4, FloorWithoutPositioningGuide.reachedChange(north(1.0), 1.0, toLevel4, level4)?.level)
        assertNull(FloorWithoutPositioningGuide.reachedChange(north(3.0), 1.0, toLevel4, level4))
    }

    @Test
    fun aCompletedChangeIsNoChange() {
        val done = listOf(marker("staircase", 1.0, 0.0, completed = true))
        assertNull(FloorWithoutPositioningGuide.reachedChange(north(1.0), 1.0, done, standard))
    }

    @Test
    fun theViewThenTheFloorThenTheVisitorAgain() {
        val guide = FloorWithoutPositioningGuide(GadSettings(levelChangeViewDuration = 3600.seconds), scope)
        val entered = mutableListOf<Int>()
        val left = mutableListOf<Int>()
        guide.onEnter = { entered += it.level }
        guide.onLeave = { left += it.level }

        guide.update(north(10.0), 1.0, toCafe, destinationId = "cafe")
        assertEquals(FloorWithoutPositioningGuide.Phase.Idle, guide.phase.value)
        guide.update(north(2.0), 1.0, toCafe, destinationId = "cafe")
        val change = (guide.phase.value as? FloorWithoutPositioningGuide.Phase.Changing)?.change ?: fail("no lift or stairs view")
        assertEquals(0, change.level)
        assertTrue(guide.isActive)

        guide.showFloor()
        assertEquals(FloorWithoutPositioningGuide.Phase.OnFloor(change), guide.phase.value)
        assertEquals(listOf(0), entered)

        // "Resume your Journey": the leg goes to the next artwork.
        guide.update(north(2.0), 1.0, emptyList(), destinationId = "artwork")
        assertEquals(FloorWithoutPositioningGuide.Phase.Idle, guide.phase.value)
        assertEquals(listOf(0), left)
    }

    @Test
    fun aDestinationLeftOnceGetsNoSecondView() {
        val guide = FloorWithoutPositioningGuide(standard, scope)
        guide.update(north(2.0), 1.0, toCafe, destinationId = "cafe")
        guide.showFloor()
        // "Centre on me" near the stairs.
        guide.leave()
        guide.update(north(2.0), 1.0, toCafe, destinationId = "cafe")
        assertEquals(FloorWithoutPositioningGuide.Phase.Idle, guide.phase.value)
        // A new route to the prayer room by the same stairs.
        guide.update(north(2.0), 1.0, toCafe, destinationId = "prayer")
        assertTrue(guide.isActive)
    }
}
