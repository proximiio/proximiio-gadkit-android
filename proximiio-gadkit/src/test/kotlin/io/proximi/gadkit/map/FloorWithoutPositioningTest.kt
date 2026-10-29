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
import kotlin.test.assertFalse
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

    /** RPR's Lottie animations, bundled with the library (PM, Monday "Elevator/Staircase animation"). */
    @Test
    fun theRidePlaysTheLiftOrStairsAnimationUpOrDown() {
        assertEquals("gad_lift_goingdown", animationName("elevator", 1, 0))
        assertEquals("gad_lift_goingup", animationName("elevator", 0, 2))
        assertEquals("gad_stairs_goingdown", animationName("staircase", 1, 0))
        assertEquals("gad_stairs_goingup", animationName("escalator", 0, 1))
    }

    /** Every ride gets the lift or stairs view, not only one to a floor without positioning (PM, Monday "Elevator/Staircase animation", 2026-10-28). */
    @Test
    fun everyRideIsReachedNearItsLiftOrStairs() {
        val toLevel4 = listOf(marker("staircase", 1.0, 3.0), marker("staircase", 3.0, 4.0))
        val ride = FloorWithoutPositioningGuide.reachedRide(north(1.0), 1.0, toLevel4, standard)
        assertEquals(4, ride?.level)
        assertEquals(1, ride?.from)
        assertNull(FloorWithoutPositioningGuide.reachedRide(north(8.0), 1.0, toLevel4, standard))
        assertNull(FloorWithoutPositioningGuide.reachedChange(north(1.0), 1.0, toLevel4, standard))
    }

    /**
     * After the view the map keeps the ride's end floor while fixes stay on the ride's floor near the
     * lift or stairs (PM, Monday "Elevator/Staircase animation", 2026-10-28).
     */
    @Test
    fun theRideFloorStaysUntilAFixOffTheStartFloor() {
        val toLevel4 = listOf(marker("staircase", 1.0, 3.0), marker("staircase", 3.0, 4.0))
        val ride = FloorWithoutPositioningGuide.reachedRide(north(1.0), 1.0, toLevel4, standard) ?: fail("no ride")
        val meters = standard.rideFloorHoldMeters
        assertEquals(20.0, meters)
        assertFalse(FloorWithoutPositioningGuide.releasesRideFloor(ride, north(10.0), 1.0, meters))
        assertFalse(FloorWithoutPositioningGuide.releasesRideFloor(ride, north(1.0), null, meters))
        assertTrue(FloorWithoutPositioningGuide.releasesRideFloor(ride, north(30.0), 1.0, meters))
        assertTrue(FloorWithoutPositioningGuide.releasesRideFloor(ride, north(1.0), 4.0, meters))
        assertTrue(FloorWithoutPositioningGuide.releasesRideFloor(ride, north(1.0), 3.0, meters))
    }

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

    /**
     * PM, Monday "POIs on floors with no positioning", 2026-10-26: on the way back the lift or stairs
     * to the next stop's floor nearest the destination is highlighted.
     */
    @Test
    fun theWayBackTakesTheNearestLiftOrStairsToTheNextFloor() {
        val origin = Coordinate(24.5, 54.4)
        val changers = listOf(
            LevelChangerPlace("far-lift", "elevator", Coordinate(24.502, 54.4), listOf(0.0, 1.0, 2.0)),
            LevelChangerPlace("near-stairs", "staircase", Coordinate(24.5001, 54.4), listOf(0.0, 1.0)),
            LevelChangerPlace("nearest-other", "elevator", Coordinate(24.50001, 54.4), listOf(2.0, 3.0)),
        )
        assertEquals("near-stairs", FloorWithoutPositioningGuide.exitChanger(origin, 0, 1, changers)?.id)
        assertEquals("far-lift", FloorWithoutPositioningGuide.exitChanger(origin, 0, 2, changers)?.id)
        assertNull(FloorWithoutPositioningGuide.exitChanger(origin, 0, 4, changers))
    }
}
