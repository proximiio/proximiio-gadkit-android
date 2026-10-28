package io.proximi.gadkit.map

import kotlin.test.assertNull
import io.proximi.sdk.core.model.ProximiioAmenity
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
    private fun place(title: String, amenity: String? = null, level: Double = 1.0, lat: Double = 0.0, placeId: String? = null) =
        VenuePlace(id = title, title = title, coordinate = Coordinate(lat, 0.0), level = level, amenityId = amenity, placeId = placeId)

    private fun cafe(title: String, level: Double, lat: Double, placeId: String? = null) =
        place(title, amenity = "a-cafe", level = level, lat = lat, placeId = placeId).copy(amenityKind = AmenityKind.CAFE)

    @Test
    fun restroomsAreNotConfused() {
        assertEquals(AmenityKind.WOMENS_RESTROOM, AmenityKind.kind("a1", "Women's Restroom"))
        assertEquals(AmenityKind.MENS_RESTROOM, AmenityKind.kind("a2", "Men's Restroom"))
        assertNull(AmenityKind.kind("a3", "Womens restroom visited"))
    }

    /**
     * A place matches by its amenity, never by its title (PM, Monday "Amenity search & Map legend
     * views", 2026-10-28: the prayer room shortcut led to the artwork "Endless Prayers XIII").
     */
    @Test
    fun aPlaceMatchesByItsAmenityNotItsTitle() {
        val amenities = listOf(
            ProximiioAmenity(id = "a-prayer", title = "Prayer Room"),
            ProximiioAmenity(id = "a-art", title = "Artwork"),
            ProximiioAmenity(id = "a-cafe", title = "Cafe"),
        )
        val places = VenuePlace.withAmenityKinds(
            listOf(place("Endless Prayers XIII", "a-art"), place("Room 4", "a-prayer"), place("The Terrace", "a-cafe"), place("Prayer room", null)),
            amenities,
        )
        assertEquals(listOf("Room 4"), places.filter(AmenityKind.PRAYER_ROOM::matches).map { it.title })
        assertEquals(listOf("The Terrace"), places.filter(AmenityKind.CAFE::matches).map { it.title })
        assertEquals(mapOf("a-prayer" to AmenityKind.PRAYER_ROOM, "a-cafe" to AmenityKind.CAFE), AmenityKind.kinds(amenities))
    }

    @Test
    fun nearestPrefersTheVisitorsLevel() {
        val places = listOf(cafe("Café A", level = 3.0, lat = 0.0001), cafe("Café B", level = 1.0, lat = 0.01))
        assertEquals("Café B", AmenityKind.CAFE.nearest(places, Coordinate(0.0, 0.0), 1.0)?.title)
        assertEquals("Café A", AmenityKind.CAFE.nearest(places, Coordinate(0.0, 0.0), 2.0)?.title)
    }

    /**
     * ProximiioGADKit 0.14.0, GAD data 2026-10-15: from level 1 of the Guggenheim the café chip chose
     * the DCT office café, on level 1 but 11 km away, over the Guggenheim café on level 0.
     */
    @Test
    fun nearestStaysInTheVenueOfTheNearestPlace() {
        val guggenheim = cafe("Café", level = 0.0, lat = 0.0001, placeId = "guggenheim")
        val dct = cafe("DCT Cafe", level = 1.0, lat = 0.1, placeId = "dct")
        assertEquals("Café", AmenityKind.CAFE.nearest(listOf(dct, guggenheim), Coordinate(0.0, 0.0), 1.0)?.title)
        assertEquals("DCT Cafe", AmenityKind.CAFE.nearest(listOf(dct, guggenheim), Coordinate(0.1, 0.0), 0.0)?.title)
    }

    // ProximiioGADKit `GuidanceTextTests` (0.11.0): the card names the route's next level change.
    private val point = Coordinate(24.5, 54.4)

    private fun marker(type: String, from: Double, to: Double, completed: Boolean = false, at: Coordinate = point) =
        LevelChangeMarker(type, fromLevel = from, toLevel = to, isCompleted = completed, coordinate = at)

    private val stairsThenLift = listOf(marker("staircase", 1.0, 3.0), marker("elevator", 3.0, 4.0))

    private fun take(changer: String, level: Int) =
        UiText.Resource("guidance_take_level_changer", listOf(UiText.Resource("guidance_level_changer_$changer"), level))

    /** Monday "Stairs&elevator icon disappeared from instructions", 2026-10-27: the card shows the icon of the next level change from the visitor's floor. */
    @Test
    fun theNextLevelChangeStartsOnTheVisitorsFloor() {
        assertEquals("elevator", GuidanceText.levelChange(stairsThenLift, visitorLevel = 3.0)?.featureType)
        assertEquals("staircase", GuidanceText.levelChange(stairsThenLift, visitorLevel = 2.0)?.featureType)
        assertNull(GuidanceText.levelChange(listOf(marker("staircase", 1.0, 3.0, completed = true)), visitorLevel = 1.0))
    }

    @Test
    fun levelChangeTitleNamesTheRoutesFirstChangeFromTheVisitorsFloor() {
        assertEquals(take("stairs", 3), GuidanceText.levelChangeTitle(stairsThenLift, visitorLevel = 1.0, stopLevel = 4))
        assertEquals(take("lift", 4), GuidanceText.levelChangeTitle(stairsThenLift, visitorLevel = 3.0, stopLevel = 4))
    }

    /**
     * ProximiioGADKit 0.13.0, Monday 2026-10-15: the SDK router splits one ride into a change per
     * floor with paths at the staircase or lift (stairs 1→3, 3→4; the lift 1→2, 2→3, 3→4). The card
     * names the end of the ride.
     */
    @Test
    fun levelChangesInTheSameStaircaseOrLiftAreOneRide() {
        val stairs = listOf(marker("staircase", 1.0, 3.0), marker("staircase", 3.0, 4.0))
        assertEquals(take("stairs", 4), GuidanceText.levelChangeTitle(stairs, visitorLevel = 1.0, stopLevel = 4))
        val lift = listOf(marker("elevator", 1.0, 2.0), marker("elevator", 2.0, 3.0), marker("elevator", 3.0, 4.0))
        assertEquals(take("lift", 4), GuidanceText.levelChangeTitle(lift, visitorLevel = 1.0, stopLevel = 4))
        val other = Coordinate(24.5003, 54.4)
        val twoStaircases = listOf(marker("staircase", 1.0, 3.0), marker("staircase", 3.0, 4.0, at = other))
        assertEquals(take("stairs", 3), GuidanceText.levelChangeTitle(twoStaircases, visitorLevel = 1.0, stopLevel = 4))
        assertEquals(4, GuidanceText.rideEnd("staircase", point, from = 1.0, to = 3.0, markers = stairs))
        assertEquals(3, GuidanceText.rideEnd("staircase", point, from = 1.0, to = 3.0, markers = emptyList()))
    }

    /** The instruction at the staircase names the end of the ride as the card does. */
    @Test
    fun theInstructionNamesTheEndOfTheRide() {
        val stairs = listOf(marker("staircase", 1.0, 3.0), marker("staircase", 3.0, 4.0))
        val guidance = RouteGuidance(
            hasArrived = false,
            isOffRoute = false,
            distanceToManoeuvreMeters = 4.0,
            manoeuvre = Manoeuvre.LevelChange("staircase", toLevel = 3.0, fromLevel = 1.0, coordinate = point),
        )
        assertEquals(take("stairs", 4), GuidanceText.instruction(guidance, stairs))
        assertEquals(take("stairs", 3), GuidanceText.instruction(guidance))
    }

    @Test
    fun levelChangeTitleSkipsCompletedChanges() {
        val markers = listOf(stairsThenLift[0].copy(isCompleted = true), stairsThenLift[1])
        assertEquals(take("lift", 4), GuidanceText.levelChangeTitle(markers, visitorLevel = null, stopLevel = 4))
    }

    @Test
    fun levelChangeTitleWithoutARouteNamesTheStopsFloor() {
        assertEquals(UiText.Resource("visit_go_to_level", listOf(4)), GuidanceText.levelChangeTitle(emptyList(), visitorLevel = 1.0, stopLevel = 4))
    }
}
