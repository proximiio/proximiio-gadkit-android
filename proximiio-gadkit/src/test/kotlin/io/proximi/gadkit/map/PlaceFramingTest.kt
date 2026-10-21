package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `PlaceFramingTests`. */
class PlaceFramingTest {
    private val metresPerDegree = 111_320.0

    @Test
    fun aNearAmenityIsShownInTheMinimumArea() {
        val visitor = Coordinate(24.5, 54.4)
        val amenity = Coordinate(24.5 + 3 / metresPerDegree, 54.4)
        val bounds = assertNotNull(PlaceFraming.bounds(listOf(visitor, amenity), 40.0))
        val height = (bounds.northEast.latitude - bounds.southWest.latitude) * metresPerDegree
        val width = (bounds.northEast.longitude - bounds.southWest.longitude) * metresPerDegree * cos(24.5 * PI / 180)
        assertTrue(abs(height - 40) < 0.01)
        assertTrue(abs(width - 40) < 0.01)
        // The centre is between the visitor and the amenity.
        assertTrue(abs((bounds.northEast.latitude + bounds.southWest.latitude) / 2 - (visitor.latitude + amenity.latitude) / 2) < 1e-9)
    }

    @Test
    fun aFarAmenityKeepsBothPointsInside() {
        val visitor = Coordinate(24.5, 54.4)
        val amenity = Coordinate(24.5005, 54.401)
        val bounds = assertNotNull(PlaceFraming.bounds(listOf(visitor, amenity), 40.0))
        assertTrue(abs(bounds.southWest.latitude - visitor.latitude) < 1e-12)
        assertTrue(abs(bounds.northEast.latitude - amenity.latitude) < 1e-12)
        assertTrue(abs(bounds.southWest.longitude - visitor.longitude) < 1e-12)
        assertTrue(abs(bounds.northEast.longitude - amenity.longitude) < 1e-12)
    }

    @Test
    fun noPointsNoArea() {
        assertNull(PlaceFraming.bounds(emptyList(), 40.0))
    }
}
