package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.StopKind
import io.proximi.gadkit.visit.StopState
import io.proximi.gadkit.visit.VisitMeta
import io.proximi.gadkit.visit.VisitPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of guggenheim-ios `VisitPlanTests.visitStoreRoundTripsAndClears`, on the stored JSON. */
class VisitStoreCodecTest {
    private val places = listOf(
        VenuePlace(id = "a", title = "Gallery 101", coordinate = Coordinate(24.5, 54.4), level = 1.0),
        VenuePlace(id = "b", title = "Artwork 1", coordinate = Coordinate(24.6, 54.5), level = 1.0),
    )

    @Test
    fun visitStoreRoundTripsAndClears() {
        val journey = VisitPlan.setting(StopState.DONE, "a", VisitPlan.journey(listOf("a", "b"), places))
        val text = VisitStoreCodec.encodeJourney(journey)
        assertEquals(journey, VisitStoreCodec.decodeJourney(text))
        assertNull(VisitStoreCodec.decodeJourney("""{"stops":[]}"""))
        assertNull(VisitStoreCodec.decodeJourney("not json"))
    }

    /** The iOS wire format: `poiID`, `floor` as the bare level, lower-case states and kinds. */
    @Test
    fun readsTheJourneyIosWrites() {
        val ios = """
            {"stops":[{"id":"a","title":"Gallery 101","coordinate":{"latitude":24.5,"longitude":54.4},
            "floor":1,"poiID":"a","kind":"planned","state":"done"},
            {"id":"d","title":"Café","coordinate":{"latitude":24.6,"longitude":54.5},"kind":"detour","state":"active","future":1}]}
        """.trimIndent()
        val journey = VisitStoreCodec.decodeJourney(ios)!!
        assertEquals(listOf(StopState.DONE, StopState.ACTIVE), journey.stops.map { it.state })
        assertEquals(1.0, journey.stops[0].level)
        assertEquals("a", journey.stops[0].poiId)
        assertEquals(StopKind.DETOUR, journey.stops[1].kind)
        assertNull(journey.stops[1].level)
        val encoded = VisitStoreCodec.encodeJourney(journey)
        assertTrue(encoded.contains("\"poiID\":\"a\""), encoded)
        assertTrue(encoded.contains("\"state\":\"done\""), encoded)
    }

    @Test
    fun metaRoundTripsWithAndWithoutSummary() {
        val meta = VisitMeta("Your journey", "2 stops")
        assertEquals(meta, VisitStoreCodec.decodeMeta(VisitStoreCodec.encodeMeta(meta)))
        assertEquals("""{"title":"Your journey","kind":"personalised"}""", VisitStoreCodec.encodeMeta(VisitMeta("Your journey", null)))
        assertEquals(VisitMeta("T", null), VisitStoreCodec.decodeMeta("""{"title":"T","extra":true}"""))
        assertNull(VisitStoreCodec.decodeMeta("{}"))
    }
}
