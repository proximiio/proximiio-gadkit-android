package io.proximi.gadkit.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `DesignSystemTests.aLongAmenityListScrolls` (iOS 1.24.1). */
class AmenityChipListTest {
    /** A list taller than its space scrolls and fades out (PM, Monday "Amenity search when list is long", 2026-10-29). */
    @Test
    fun aLongAmenityListScrolls() {
        assertTrue(AmenityChipListLayout.scrolls(contentHeight = 400, visibleHeight = 300))
        assertFalse(AmenityChipListLayout.scrolls(contentHeight = 300, visibleHeight = 300))
        assertFalse(AmenityChipListLayout.scrolls(contentHeight = 300, visibleHeight = 0))
    }
}
