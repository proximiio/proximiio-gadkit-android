package io.proximi.gadkit.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `VenueLevelTests`. */
class VenueLevelTest {
    @Test
    fun aMezzanineIsItsOwnLevel() {
        assertTrue(VenueLevel.same(1.5, 1.5))
        assertFalse(VenueLevel.same(1.5, 2.0))
        assertFalse(VenueLevel.same(1.5, 1.0))
        assertFalse(VenueLevel.same(-0.5, -1.0))
    }

    @Test
    fun floatingPointNoiseIsTheSameLevel() {
        assertTrue(VenueLevel.same(1.0, 1.000_001))
        assertTrue(VenueLevel.same(0.0, -0.000_001))
    }

    @Test
    fun anUnknownLevelMatchesAnyLevel() {
        assertTrue(VenueLevel.matches(null, 1.5))
        assertTrue(VenueLevel.matches(2.0, null))
        assertFalse(VenueLevel.matches(2.0, 1.5))
    }
}
