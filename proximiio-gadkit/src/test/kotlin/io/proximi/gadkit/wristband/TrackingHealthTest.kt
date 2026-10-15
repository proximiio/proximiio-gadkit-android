package io.proximi.gadkit.wristband

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.VenuePlace
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Port of guggenheim-ios `TrackingHealthTests` (in WristbandPairingTests.swift). */
class TrackingHealthTest {
    private val now = Instant.ofEpochSecond(1_000_000)

    @Test
    fun statesFollowAvailability() {
        assertEquals(TrackingHealth.WAITING, TrackingHealth.evaluate(wasEverAvailable = false, unavailableSince = now, now = now))
        assertEquals(TrackingHealth.TRACKING, TrackingHealth.evaluate(wasEverAvailable = true, unavailableSince = null, now = now))
        assertEquals(TrackingHealth.TRACKING, TrackingHealth.evaluate(true, now.minusSeconds(1), now))
        assertEquals(TrackingHealth.TRACKING, TrackingHealth.evaluate(true, now.minusSeconds(109), now))
        assertEquals(TrackingHealth.LOST_PLACE, TrackingHealth.evaluate(true, now.minusSeconds(110), now))
        assertEquals(TrackingHealth.LOST_PLACE, TrackingHealth.evaluate(true, now.minusSeconds(169), now))
        assertEquals(TrackingHealth.STOPPED_RESPONDING, TrackingHealth.evaluate(true, now.minusSeconds(170), now))
    }

    @Test
    fun nearestPlaceIsOnTheSameLevel() {
        val places = listOf(
            VenuePlace(id = "a", title = "Gallery 1", coordinate = Coordinate(24.5000, 54.4000), level = 1.0),
            VenuePlace(id = "b", title = "Gallery 2", coordinate = Coordinate(24.5010, 54.4010), level = 1.0),
            VenuePlace(id = "c", title = "Gallery 301", coordinate = Coordinate(24.5001, 54.4001), level = 3.0),
        )
        val here = Coordinate(24.5002, 54.4002)
        assertEquals("a", VenuePlace.nearest(here, 1.0, places)?.id)
        assertEquals("c", VenuePlace.nearest(here, 3.0, places)?.id)
        assertNull(VenuePlace.nearest(null, 1.0, places))
    }
}
