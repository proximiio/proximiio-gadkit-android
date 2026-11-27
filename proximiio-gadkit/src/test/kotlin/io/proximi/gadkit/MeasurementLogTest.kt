package io.proximi.gadkit

import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.map.AmenityShortcut
import io.proximi.gadkit.map.VenuePlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** iOS `MeasurementLogTests` (1.74.0): the field log lines for the measurements of the contract KPIs. */
class MeasurementLogTest {
    private val cafe = VenuePlace(id = "cafe", title = "Café", coordinate = Coordinate(24.5398, 54.3992), level = 0.0, amenityId = "a")

    @Test
    fun aCategoryLineNamesTheCountTheNearestPlaceAndTheTime() {
        assertEquals(
            "search category Café (journey): 3 places · nearest Café L0 · 2 ms",
            MeasurementLog.categoryLine("Café", "journey", results = 3, nearest = cafe, milliseconds = 2),
        )
        assertEquals(
            "search category Lockers (museum): 0 places · nearest none · 0 ms",
            MeasurementLog.categoryLine("Lockers", "museum", results = 0, nearest = null, milliseconds = 0),
        )
    }

    @Test
    fun aCategoryOfTheDesignHasItsNameWithoutTheLanguage() {
        assertEquals("cafe", MeasurementLog.title(AmenityShortcut.Kind(AmenityKind.CAFE)))
        assertEquals("Café", MeasurementLog.title(AmenityShortcut.Amenity("a", "Café", icon = null)))
    }

    @Test
    fun aTextLineHoldsTheLengthOfTheQueryNotItsText() {
        assertEquals(
            "search text 5 chars (museum): 12 of 247 places · 1 ms",
            MeasurementLog.textLine(characters = 5, screen = "museum", results = 12, places = 247, milliseconds = 1),
        )
    }

    @Test
    fun aLoggedSearchGivesThePlacesOfTheUnloggedOne() {
        val places = listOf(cafe, VenuePlace(id = "shop", title = "Shop", coordinate = cafe.coordinate, level = 1.0))
        assertEquals(VenuePlace.matching("caf", places), MeasurementLog.places("caf", places, screen = "test"))
        assertEquals(places, MeasurementLog.places("", places, screen = "test"))
    }

    @Test
    fun aLoggedCategoryGivesThePlaceOfTheUnloggedOne() {
        val shortcut = AmenityShortcut.Amenity("a", "Café", icon = null)
        assertEquals(shortcut.nearest(listOf(cafe), null, null), MeasurementLog.nearest(shortcut, listOf(cafe), null, null, screen = "test"))
    }

    @Test
    fun theListAndDestinationLines() {
        assertEquals("explore list spaces: 19 items · shown in 84 ms", MeasurementLog.listLine("spaces", items = 19, milliseconds = 84))
        assertEquals("destination Café: routed in 412 ms", MeasurementLog.destinationLine("Café", "routed", milliseconds = 412))
    }

    @Test
    fun aDrawnLineHoldsTheDotItsPositionAndTheRelayPositionWithTheirTimes() {
        var sample = MeasurementLog.DrawnSample(drawnLatitude = 24.53980721, drawnLongitude = 54.39921712)
        val now = 1_794_822_001_123
        assertEquals(
            "drawn position journey: 24.5398072,54.3992171 L- · at 1794822001123 · fix - · relay -",
            MeasurementLog.drawnLine(sample, "journey", now),
        )
        sample = sample.copy(
            fixLatitude = 24.539808, fixLongitude = 54.399218, level = 1.0, fixTimeMillis = 1_794_822_000_500,
            relayLatitude = 24.539808, relayLongitude = 54.399218, relayTimeMillis = 1_794_822_000_400,
        )
        assertEquals(
            "drawn position journey: 24.5398072,54.3992171 L1 · at 1794822001123" +
                " · fix 24.5398080,54.3992180 ts 1794822000500 · relay 24.5398080,54.3992180 ts 1794822000400",
            MeasurementLog.drawnLine(sample, "journey", now),
        )
    }

    @Test
    fun theRateLineCountsThePositionsOfAMinute() {
        val rate = MeasurementLog.RateLog()
        assertNull(rate.position(100.0))
        for (second in 1 until 60) assertNull(rate.position(100.0 + second))
        assertEquals("relay positions: 60 in 60.4 s", rate.position(160.4))
        // The next minute counts from the position that ended the last one.
        assertNull(rate.position(161.0))
        assertEquals("relay positions: 2 in 89.6 s", rate.position(250.0))
    }
}
