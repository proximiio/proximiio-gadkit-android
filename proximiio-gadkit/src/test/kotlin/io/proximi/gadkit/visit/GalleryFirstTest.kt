package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.UiText
import io.proximi.gadkit.map.Gallery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GalleryFirstTest {
    private val square = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 1.0), Coordinate(1.0, 1.0), Coordinate(1.0, 0.0))
    private val g106 = Gallery("106", "Gallery 106", 1.0, square)
    private val g107 = Gallery("107", "Gallery 107", 1.0, square.map { Coordinate(it.latitude + 2, it.longitude) })

    @Test
    fun outsideTheStopsGalleryNamesTheGallery() {
        assertEquals(g106, GalleryFirst.galleryAhead("s1", g106, reachedStopId = null))
        assertEquals("s1", GalleryFirst.reachedStopId("s1", g106, visitorGallery = g106, reachedStopId = null))
        assertNull(GalleryFirst.reachedStopId("s1", g106, visitorGallery = g107, reachedStopId = null))
        assertNull(GalleryFirst.reachedStopId("s1", g106, visitorGallery = null, reachedStopId = null))
    }

    @Test
    fun theLatchHoldsUntilTheStopChanges() {
        val reached = GalleryFirst.reachedStopId("s1", g106, g106, null)
        assertNull(GalleryFirst.galleryAhead("s1", g106, reached))
        // Walking out of the gallery again keeps the artwork.
        assertEquals("s1", GalleryFirst.reachedStopId("s1", g106, null, reached))
        assertNull(GalleryFirst.galleryAhead("s1", g106, GalleryFirst.reachedStopId("s1", g106, null, reached)))
        // The next stop names its gallery again.
        assertEquals(g107, GalleryFirst.galleryAhead("s2", g107, reached))
        assertEquals("s2", GalleryFirst.reachedStopId("s2", g107, g107, reached))
    }

    @Test
    fun aStopOutsideEveryGalleryShowsTheArtwork() {
        assertNull(GalleryFirst.galleryAhead("s1", null, null))
        assertNull(GalleryFirst.reachedStopId("s1", null, g106, null))
        assertNull(GalleryFirst.galleryAhead(null, g106, null))
    }

    @Test
    fun cardTextWithAGalleryAhead() {
        val walking = VisitCardState.Walking("Tongari-kun")
        assertEquals(UiText.Resource("visit_next_stop"), VisitCardText.caption(walking, isLastOpenStop = true, gallery = "Gallery 107", galleryAhead = "Gallery 106"))
        assertEquals(UiText.Verbatim("Gallery 106"), VisitCardText.title(walking, galleryAhead = "Gallery 106"))
        assertFalse(VisitCardText.showsStopNumber(walking, galleryAhead = "Gallery 106"))
        val otherLevel = VisitCardState.NextStopOnOtherLevel("Tongari-kun", 3)
        assertEquals(UiText.Resource("visit_next_stop_is", listOf("Gallery 106")), VisitCardText.caption(otherLevel, false, null, "Gallery 106"))
        assertEquals(UiText.Resource("visit_go_to_level", listOf(3)), VisitCardText.title(otherLevel, "Gallery 106"))
        assertFalse(VisitCardText.showsStopNumber(otherLevel, "Gallery 106"))
        assertEquals(UiText.Resource("visit_next_stop"), VisitCardText.caption(VisitCardState.Starting, false, null, "Gallery 106"))
        assertEquals(UiText.Resource("visit_your_journey"), VisitCardText.title(VisitCardState.Starting, "Gallery 106"))
    }

    @Test
    fun cardTextInsideTheStopsGallery() {
        val walking = VisitCardState.Walking("Tongari-kun")
        assertEquals(UiText.Resource("visit_gallery_x", listOf("Gallery 106")), VisitCardText.caption(walking, false, gallery = "Gallery 106", galleryAhead = null))
        assertEquals(UiText.Verbatim("Tongari-kun"), VisitCardText.title(walking, galleryAhead = null))
        assertTrue(VisitCardText.showsStopNumber(walking, galleryAhead = null))
        assertTrue(VisitCardText.showsStopNumber(VisitCardState.AtStop("Tongari-kun"), galleryAhead = "Gallery 106"))
    }
}
