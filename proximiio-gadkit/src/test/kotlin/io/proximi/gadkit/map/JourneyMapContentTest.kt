package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.StopState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fill rules of iOS `VisitView.refreshMapLayers`. */
class JourneyMapContentTest {
    /** A 0.001° square with its south-west corner at ([latitude], [longitude]). */
    private fun gallery(id: String, latitude: Double, longitude: Double, level: Double = 1.0) = Gallery(
        id = id,
        name = id,
        level = level,
        ring = listOf(
            Coordinate(latitude, longitude),
            Coordinate(latitude, longitude + 0.001),
            Coordinate(latitude + 0.001, longitude + 0.001),
            Coordinate(latitude + 0.001, longitude),
        ),
    )

    private val a = gallery("A", 24.500, 54.400)
    private val b = gallery("B", 24.510, 54.400)
    private val c = gallery("C", 24.520, 54.400)

    private fun stop(id: String, inGallery: Gallery, state: StopState = StopState.PENDING, level: Double? = 1.0) = JourneyStop(
        id = id,
        title = id,
        coordinate = Coordinate(inGallery.ring[0].latitude + 0.0005, inGallery.ring[0].longitude + 0.0005),
        level = level,
        poiId = null,
        state = state,
    )

    @Test
    fun theActiveStopsGalleryIsTheDestinationAndDoneStopsMarkTheirGalleryVisited() {
        val journey = Journey(listOf(stop("1", a, StopState.DONE), stop("3", b, StopState.ACTIVE), stop("4", c)))
        val fills = JourneyMapContent.fills(journey, "3", listOf(a, b, c))
        assertEquals(listOf(GalleryFill(a, false), GalleryFill(b, true)), fills)
    }

    @Test
    fun galleriesTheVisitorWasInAreVisitedAndTheDestinationWins() {
        val journey = Journey(listOf(stop("3", b, StopState.ACTIVE)))
        val fills = JourneyMapContent.fills(journey, "3", listOf(a, b, c), visitedGalleryIds = setOf("B", "C"))
        assertEquals(listOf(GalleryFill(b, true), GalleryFill(c, false)), fills)
    }

    @Test
    fun noActiveStopMeansNoDestinationFill() {
        val journey = Journey(listOf(stop("1", a)))
        assertEquals(emptyList(), JourneyMapContent.fills(journey, null, listOf(a, b, c)))
    }

    @Test
    fun aStopOnAnotherLevelIsNotInTheGallery() {
        val journey = Journey(listOf(stop("1", a, StopState.ACTIVE, level = 2.0)))
        assertEquals(emptyList(), JourneyMapContent.fills(journey, "1", listOf(a)))
    }

    @Test
    fun fillsShowOnTheirLevelOnly() {
        val fills = listOf(GalleryFill(a, true), GalleryFill(gallery("D", 0.0, 0.0, level = 2.0), false))
        assertEquals(listOf("A"), JourneyMapContent.fillsOn(1.0, fills).map { it.gallery.id })
    }

    @Test
    fun theMapDrawsInArabicOrEnglish() {
        assertEquals("ar", MapLanguage.forAppLanguage("ar"))
        assertEquals("ar", MapLanguage.forAppLanguage("ar-AE"))
        assertEquals("en", MapLanguage.forAppLanguage("en-GB"))
        assertEquals("en", MapLanguage.forAppLanguage("fr"))
        assertEquals("en", MapLanguage.forAppLanguage(null))
    }
}
