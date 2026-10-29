package io.proximi.gadkit.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `DesignSystemTests.theMapListSelectionSlidesWithTheFinger` (iOS 1.21.0). */
class MapListSelectionTest {
    /** The selection follows the finger and stays inside the control (Monday "Native elements", 2026-10-28 evening). */
    @Test
    fun theMapListSelectionSlidesWithTheFinger() {
        assertEquals(30f, MapListSelection.offset(showsList = false, slide = 30f, width = 71f))
        assertEquals(0f, MapListSelection.offset(showsList = false, slide = -30f, width = 71f))
        assertEquals(21f, MapListSelection.offset(showsList = true, slide = -50f, width = 71f))
        assertEquals(71f, MapListSelection.offset(showsList = true, slide = 30f, width = 71f))
        // A finger that hardly moves picks the part under it; a slide the part the selection ends over.
        assertTrue(MapListSelection.showsList(slide = 2f, position = 100f, showsList = false, width = 71f, threshold = 8f))
        assertFalse(MapListSelection.showsList(slide = 0f, position = 20f, showsList = true, width = 71f, threshold = 8f))
        assertTrue(MapListSelection.showsList(slide = 40f, position = 20f, showsList = false, width = 71f, threshold = 8f))
        assertFalse(MapListSelection.showsList(slide = 30f, position = 20f, showsList = false, width = 71f, threshold = 8f))
        assertFalse(MapListSelection.showsList(slide = -50f, position = 120f, showsList = true, width = 71f, threshold = 8f))
    }
}
