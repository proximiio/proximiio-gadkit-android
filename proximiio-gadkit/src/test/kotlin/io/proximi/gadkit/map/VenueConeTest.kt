package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `VenueConeTests` (iOS 1.22.0): the cones with the design's metal gradient (PM, Monday "Cone visualization", 2026-10-29). */
class VenueConeTest {
    /** A cone about 22 m wide and 89 m long, pointing north. GeoJSON pairs are longitude, latitude. */
    private fun feature(id: String, type: String, level: Double) = VenueFeature(
        id = id,
        type = type,
        geometry = Geometry("Polygon", listOf(listOf(listOf(54.3980, 24.5400), listOf(54.3982, 24.5400), listOf(54.3981, 24.5408)))),
        properties = mapOf("type" to type, "level" to level),
        level = level,
    )

    @Test
    fun onlyConePolygonsAreCones() {
        val cones = VenueCone.all(listOf(feature("c1", "cone", 2.0), feature("g1", "gallery", 2.0)))
        assertEquals(listOf("c1"), cones.map { it.id })
        assertEquals(2.0, cones.first().level)
        assertEquals(Coordinate(24.54, 54.398), cones.first().ring.first())
    }

    /** A tall cone gets a gradient from side to side, not from tip to base. */
    @Test
    fun theGradientRunsAcrossTheCone() {
        val tall = listOf(ConeShading.Point(0.0, 100.0), ConeShading.Point(20.0, 100.0), ConeShading.Point(10.0, 0.0))
        val (start, end) = ConeShading.gradientAxis(tall)
        assertTrue(abs(end.x - start.x) > abs(end.y - start.y))
        val wide = listOf(ConeShading.Point(0.0, 0.0), ConeShading.Point(0.0, 20.0), ConeShading.Point(100.0, 10.0))
        val (from, to) = ConeShading.gradientAxis(wide)
        assertTrue(abs(to.y - from.y) > abs(to.x - from.x))
    }

    /** The image covers the cone's bounds at 4 px per metre. */
    @Test
    fun theImageCoversTheConeBounds() {
        val cone = VenueCone.all(listOf(feature("c1", "cone", 1.0))).first()
        val frame = assertNotNull(ConeShading.frame(cone))
        assertEquals(24.5408, frame.north)
        assertEquals(24.54, frame.south)
        assertEquals(54.398, frame.west)
        assertEquals(54.3982, frame.east)
        assertTrue(frame.height in 301..399, "height ${frame.height}")
        assertTrue(frame.width in 71..99, "width ${frame.width}")
        // The tip of the cone is at the top in the middle.
        assertEquals(0.0, frame.points[2].y, 1e-9)
    }
}
