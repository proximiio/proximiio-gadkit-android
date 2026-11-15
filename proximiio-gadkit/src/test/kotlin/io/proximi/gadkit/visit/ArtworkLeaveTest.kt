package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.GadSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** iOS `ArtworkLeaveTests` (1.41.0). */
class ArtworkLeaveTest {
    @Test
    fun theDistanceToAnArtworkAreaIsMeasuredFromItsBorder() {
        val center = Coordinate(24.5, 54.4)
        val circle = ArtworkArea(center, radius = 3.0)
        assertEquals(0.0, circle.distanceTo(center))
        val north10 = Coordinate(24.5 + 10 / 111_320.0, 54.4)
        assertTrue(abs(circle.distanceTo(north10) - 7) < 0.1)
        assertEquals(Double.POSITIVE_INFINITY, ArtworkArea(null, radius = 0.0).distanceTo(center))

        // A square of about 4 m: corners 2 m east, west, north and south of the centre.
        val dLat = 2 / 111_320.0
        val dLng = 2 / (111_320.0 * cos(24.5 * PI / 180))
        val square = ArtworkArea(
            null,
            radius = 0.0,
            polygon = listOf(
                listOf(54.4 - dLng, 24.5 - dLat), listOf(54.4 + dLng, 24.5 - dLat),
                listOf(54.4 + dLng, 24.5 + dLat), listOf(54.4 - dLng, 24.5 + dLat),
            ),
        )
        assertEquals(0.0, square.distanceTo(center))
        // 6 m east of the centre: 4 m from the east side.
        val east6 = Coordinate(24.5, 54.4 + 3 * dLng)
        assertTrue(abs(square.distanceTo(east6) - 4) < 0.05)
        // 5 m east and 6 m north of the centre: 3 m and 4 m from the corner, 5 m away.
        val corner = Coordinate(24.5 + 3 * dLat, 54.4 + 2.5 * dLng)
        assertTrue(abs(square.distanceTo(corner) - 5) < 0.05)
    }

    @Test
    fun anArtworkIsLeftBeyondTheVisitDistance() {
        val settings = GadSettings()
        assertEquals(5.0, settings.artworkVisitedMeters)
        assertEquals(2.seconds, settings.artworkVisitedSeconds)
        assertFalse(ArtworkArrival.isBeyondVisit(0.0, settings.artworkVisitedMeters))
        assertFalse(ArtworkArrival.isBeyondVisit(5.0, settings.artworkVisitedMeters))
        assertTrue(ArtworkArrival.isBeyondVisit(5.1, settings.artworkVisitedMeters))
        assertTrue(ArtworkArrival.isBeyondVisit(Double.POSITIVE_INFINITY, settings.artworkVisitedMeters))
        assertTrue(ArtworkArrival.isBeyondVisit(0.1, settings.copy(artworkVisitedMeters = 0.0).artworkVisitedMeters))
    }
}
