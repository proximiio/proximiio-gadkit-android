package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of guggenheim-ios `GuidanceTextTests`. Parameterised Swift tests loop over their arguments. */
class GuidanceTextTest {
    @Test
    fun walkingTimeRoundsUpWholeMinutes() {
        val lessThanAMinute = UiText.Resource("guidance_less_than_one_minute")
        fun minutes(n: Int) = UiText.Resource("guidance_minutes", listOf(n))
        val cases = listOf(0.0 to lessThanAMinute, 71.0 to lessThanAMinute, 72.0 to minutes(1), 73.0 to minutes(2), 150.0 to minutes(3))
        for ((metres, expected) in cases) {
            assertEquals(expected, GuidanceText.walkingTime(metres), "$metres m")
        }
    }

    @Test
    fun remainingCombinesDistanceAndTime() {
        assertEquals(
            GuidanceText.Remaining(metres = 70, time = UiText.Resource("guidance_less_than_one_minute")),
            GuidanceText.remaining(70.4),
        )
    }

    @Test
    fun floorLabel() {
        for ((level, expected) in listOf(0.0 to "L0", 1.0 to "L1", -1.0 to "L-1", 2.4 to "L2")) {
            assertEquals(expected, GuidanceText.floorLabel(level))
        }
    }

    @Test
    fun levelChangerNames() {
        assertEquals(UiText.Resource("guidance_level_changer_lift"), GuidanceText.levelChanger("elevator"))
        assertEquals(UiText.Resource("guidance_level_changer_stairs"), GuidanceText.levelChanger("staircase"))
        assertEquals(UiText.Verbatim("unknown"), GuidanceText.levelChanger("unknown"))
    }
}

/** Port of guggenheim-ios `AmenityKindTests` (in GuidanceTextTests.swift). */
class AmenityKindTest {
    private fun place(title: String, amenity: String? = null, level: Double = 1.0, lat: Double = 0.0) =
        VenuePlace(id = title, title = title, coordinate = Coordinate(lat, 0.0), level = level, amenityId = amenity)

    @Test
    fun restroomsAreNotConfused() {
        assertTrue(AmenityKind.WOMENS_RESTROOM.matches(place("Women's Restroom")))
        assertFalse(AmenityKind.MENS_RESTROOM.matches(place("Women's Restroom")))
        assertTrue(AmenityKind.MENS_RESTROOM.matches(place("Men's Restroom")))
        assertFalse(AmenityKind.WOMENS_RESTROOM.matches(place("Men's Restroom")))
    }

    @Test
    fun matchesByAmenityID() {
        assertTrue(AmenityKind.CAFE.matches(place("The Terrace", amenity = "food:cafe")))
        assertTrue(AmenityKind.PRAYER_ROOM.matches(place("Room 4", amenity = "services:prayer_room")))
        assertFalse(AmenityKind.CAFE.matches(place("Gallery 101")))
    }

    @Test
    fun nearestPrefersTheVisitorsLevel() {
        val places = listOf(place("Café A", level = 3.0, lat = 0.0001), place("Café B", level = 1.0, lat = 0.01))
        assertEquals("Café B", AmenityKind.CAFE.nearest(places, Coordinate(0.0, 0.0), 1.0)?.title)
        assertEquals("Café A", AmenityKind.CAFE.nearest(places, Coordinate(0.0, 0.0), 2.0)?.title)
    }
}
