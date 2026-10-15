package io.proximi.gadkit.map

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
