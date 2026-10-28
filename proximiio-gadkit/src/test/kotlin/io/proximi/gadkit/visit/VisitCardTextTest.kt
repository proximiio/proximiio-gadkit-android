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
        assertEquals(UiText.Resource("visit_go_to_level", listOf(3)), VisitCardText.title(level))
        assertEquals(UiText.Verbatim("A"), VisitCardText.title(VisitCardState.AtStop("A")))
        // No guidance on another floor: no instruction (no "Go to the lift" since ProximiioGADKit 0.11.0).
        assertNull(VisitCardText.instruction(level, guidance = null, title = VisitCardText.title(level)))
        // At the level change the instruction repeats the title and is left out.
        val take = UiText.Resource("guidance_take_level_changer", listOf(UiText.Resource("guidance_level_changer_stairs"), 3))
        assertNull(VisitCardText.instruction(level, guidance = take, title = take))
        val turn = UiText.Resource("guidance_turn_left", listOf(5))
        assertEquals(turn, VisitCardText.instruction(level, guidance = turn, title = take))
        assertEquals(turn, VisitCardText.instruction(VisitCardState.Walking("A"), guidance = turn, title = UiText.Verbatim("A")))
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
        val cafe = VenuePlace(id = "c", title = "Café", coordinate = Coordinate(0.0, 0.0), level = 0.0, amenityId = "food:cafe", amenityKind = AmenityKind.CAFE)
        val kiosk = VenuePlace(id = "k", title = "Ticket Kiosk", coordinate = Coordinate(0.0, 0.0), level = 0.0)
        val suggestions = BeforeYouGo.suggestions(places + kiosk + cafe)
        assertTrue(AmenityKind.CAFE.matches(cafe))
        assertEquals(listOf("c", "k"), suggestions.map { it.id })
        assertEquals(emptyList(), BeforeYouGo.suggestions(places))
    }
}

/** The quiet period of an answered location question ("Probably at …"). */
class LocationQuestionTest {
    @Test
    fun answeredKindsStayQuietForTwoMinutes() {
        val answered = mapOf("low" to Instant.ofEpochSecond(1_000))
        assertTrue(LocationAmbiguity.isQuiet("low", answered, Instant.ofEpochSecond(1_119)))
        assertFalse(LocationAmbiguity.isQuiet("low", answered, Instant.ofEpochSecond(1_120)))
        assertFalse(LocationAmbiguity.isQuiet("multiple", answered, Instant.ofEpochSecond(1_001)))
    }
}
