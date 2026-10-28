package io.proximi.gadkit.map

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of ProximiioGADKit `AmenityIconTests.gadAmenitiesMapToTheirKinds`. The marker size needs
 * Android graphics; the UI tests cover it.
 */
class AmenityIconTest {
    @Test
    fun gadAmenitiesMapToTheirKinds() {
        val cases = listOf(
            "Women's Restroom" to AmenityKind.WOMENS_RESTROOM,
            "Men's Restroom" to AmenityKind.MENS_RESTROOM,
            "Cafe" to AmenityKind.CAFE,
            "Helpdesk" to AmenityKind.HELP_DESK,
            "Prayer Room" to AmenityKind.PRAYER_ROOM,
            "Staircase" to AmenityKind.STAIRS,
            "Elevator" to AmenityKind.ELEVATOR,
            "Museum Shop" to AmenityKind.MUSEUM_SHOP,
            "Womens restroom visited" to null,
            "Womens restroom distination" to null,
            "Artwork" to null,
            "Gallery" to null,
            "Escalator" to null,
        )
        for ((title, kind) in cases) assertEquals(kind, AmenityKind.kind("org:id", title), title)
    }

    /**
     * The map markers of the iOS package (`Icon*Marker.svg`): a white circle with a #EEEDED edge
     * and a #2B2B2B glyph. The SVG files name the circle colour `white`; a conversion to a vector
     * drawable made it black (PM 2026-10-15).
     */
    @Test
    fun amenityMarkersAreWhiteCirclesWithDarkGlyphs() {
        val markers = File("src/main/res/drawable").listFiles { file -> file.name.endsWith("_marker.xml") }.orEmpty()
        assertEquals(8, markers.size)
        for (marker in markers) {
            val fills = Regex("android:fillColor=\"([^\"]+)\"").findAll(marker.readText()).map { it.groupValues[1] }.toList()
            assertEquals("#FFFFFFFF", fills.first(), marker.name)
            assertTrue(fills.drop(1).all { it == "#FF2B2B2B" }, marker.name)
        }
    }

    /** PM, 2026-10-27: a map icon is the data icon's pixel size in dp; without an icon 120 × 120, the venue data's icons. */
    @Test
    fun aMapIconTakesTheDataIconsPixelSize() {
        assertEquals(120 to 120, MapStyle.styleIconSize(null, null))
        assertEquals(30 to 30, MapStyle.styleIconSize(30, 30))
        assertEquals(120 to 60, MapStyle.styleIconSize(120, 60))
    }
}
