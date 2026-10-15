package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.UiText
import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.map.VenuePlace
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stop card text, the exit rule and Before You Go of iOS `VisitView` (no iOS tests). */
class VisitCardTextTest {
    private val places = listOf(
        VenuePlace(id = "a", title = "Gallery 101", coordinate = Coordinate(0.0, 0.0), level = 1.0),
        VenuePlace(id = "b", title = "Gallery 304", coordinate = Coordinate(0.0, 0.0), level = 3.0),
    )

    @Test
    fun captionPerState() {
        val walking = VisitCardState.Walking("Gallery 101")
        assertEquals(UiText.Resource("visit_last_stop"), VisitCardText.caption(walking, isLastOpenStop = true, gallery = "Gallery 1"))
        assertEquals(UiText.Resource("visit_gallery_x", listOf("Gallery 1")), VisitCardText.caption(walking, false, "Gallery 1"))
        assertEquals(UiText.Resource("visit_next_stop"), VisitCardText.caption(VisitCardState.Starting, false, null))
        assertEquals(
            UiText.Resource("visit_next_stop_is", listOf("Gallery 304")),
            VisitCardText.caption(VisitCardState.NextStopOnOtherLevel("Gallery 304", 3), isLastOpenStop = true, gallery = null),
        )
        assertEquals(UiText.Resource("visit_current_artwork"), VisitCardText.caption(VisitCardState.AtStop("A"), true, "G"))
        assertEquals(UiText.Resource("visit_journey_complete_caps"), VisitCardText.caption(VisitCardState.Finished, false, null))
    }

    @Test
    fun titleGuidanceAndAction() {
        val level = VisitCardState.NextStopOnOtherLevel("Gallery 304", 3)
        assertEquals(UiText.Resource("visit_take_the_lift_to_level", listOf(3)), VisitCardText.title(level))
        assertEquals(UiText.Verbatim("A"), VisitCardText.title(VisitCardState.AtStop("A")))
        assertEquals(UiText.Resource("visit_go_to_the_lift"), VisitCardText.instructionWithoutGuidance(level))
        assertNull(VisitCardText.instructionWithoutGuidance(VisitCardState.Walking("A")))
        assertTrue(VisitCardText.showsGuidance(level))
        assertFalse(VisitCardText.showsGuidance(VisitCardState.AtStop("A")))
    }

    @Test
    fun backAsksOnlyWhileTheVisitIsUnderWay() {
        var journey = VisitPlan.journey(listOf("a", "b"), places)
        assertFalse(VisitPlan.asksBeforeLeaving(journey, isFinished = false))
        journey = VisitPlan.setting(StopState.SKIPPED, "a", journey)
        assertTrue(VisitPlan.asksBeforeLeaving(journey, isFinished = false))
        assertFalse(VisitPlan.asksBeforeLeaving(journey, isFinished = true))
        journey = VisitPlan.setting(StopState.DONE, "b", journey)
        assertEquals(VisitPlan.Progress(visited = 1, total = 1), VisitPlan.progress(journey))
    }

    @Test
    fun beforeYouGoSuggestsCafeShopAndKiosk() {
        val cafe = VenuePlace(id = "c", title = "Café", coordinate = Coordinate(0.0, 0.0), level = 0.0, amenityId = "food:cafe")
        val kiosk = VenuePlace(id = "k", title = "Ticket Kiosk", coordinate = Coordinate(0.0, 0.0), level = 0.0)
        val suggestions = BeforeYouGo.suggestions(places + kiosk + cafe)
        assertTrue(AmenityKind.CAFE.matches(cafe))
        assertEquals(listOf("c", "k"), suggestions.map { it.id })
        assertEquals(emptyList(), BeforeYouGo.suggestions(places))
    }
}

/** The "Where are you?" choices of iOS `VisitView` (EC04b, EC04c). */
class LocationChoiceTest {
    private val stops = listOf(
        JourneyStop("a", "Mirror", Coordinate(0.0, 0.0), 1.0, "a", StopState.DONE),
        JourneyStop("b", "Room", Coordinate(0.0, 0.0), 2.0, "b", StopState.ACTIVE),
        JourneyStop("c", "Wall", Coordinate(0.0, 0.0), null, "c"),
    )

    @Test
    fun lowConfidenceIsASheetNotAChoice() {
        assertNull(LocationChoice.of(LocationAmbiguity.LowConfidence(stops[1])))
    }

    @Test
    fun betweenGalleriesNamesTheGallery() {
        val gallery = VenuePlace(id = "g", title = "Gallery 2", coordinate = Coordinate(0.0, 0.0), level = 1.0)
        val choice = LocationChoice.of(LocationAmbiguity.BetweenGalleries(listOf(gallery)))!!
        assertEquals(LocationChoice.Action.InGallery("Gallery 2"), choice.options.first().action)
        assertNull(choice.caption)
    }

    @Test
    fun manualListsOpenStopsWithTheirLevel() {
        val choice = LocationChoice.manual(Journey(stops))
        assertEquals(LocationChoice.MANUAL, choice.kind)
        assertEquals(listOf("b", "c", "somewhere-else"), choice.options.map { it.id })
        assertEquals(UiText.Resource("visit_level_n", listOf(2)), choice.options[0].subtitle)
        assertNull(choice.options[1].subtitle)
    }

    @Test
    fun answeredKindsStayQuietForTwoMinutes() {
        val answered = mapOf("low" to Instant.ofEpochSecond(1_000))
        assertTrue(LocationChoice.isQuiet("low", answered, Instant.ofEpochSecond(1_119)))
        assertFalse(LocationChoice.isQuiet("low", answered, Instant.ofEpochSecond(1_120)))
        assertFalse(LocationChoice.isQuiet("multiple", answered, Instant.ofEpochSecond(1_001)))
    }
}
