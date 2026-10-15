package io.proximi.gadkit.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `ArtworkMetadataTests`. */
class ArtworkMetadataTest {
    private fun place(metadata: Map<String, Any?>): VenuePlace = assertNotNull(
        VenuePlace.from(
            VenueFeature(
                id = "a",
                type = "poi",
                geometry = Geometry("Point", listOf(54.4, 24.5)),
                properties = mapOf("title" to "Untitled (Mirrored Room)", "metadata" to metadata),
                level = 1.0,
            ),
            language = "en",
        ),
    )

    @Test
    fun readsArtistAndBadges() {
        val place = place(mapOf("artist_name" to "Lucas Samaras", "has_ar" to true, "has_audio" to "true"))
        assertEquals("Lucas Samaras", place.artist)
        assertTrue(place.hasAR)
        assertTrue(place.hasAudio)
    }

    @Test
    fun theArtistCanBeAnI18nObject() {
        val place = place(mapOf("artist_name" to mapOf("en" to "Lucas Samaras", "ar" to "لوكاس ساماراس")))
        assertEquals("Lucas Samaras", place.artist)
    }

    @Test
    fun withoutTheKeysThereIsNoArtistAndNoBadge() {
        val place = place(mapOf("artwork_id" to "x", "artist_id" to "y"))
        assertNull(place.artist)
        assertFalse(place.hasAR)
        assertFalse(place.hasAudio)
    }

    @Test
    fun flagValues() {
        assertTrue(VenuePlace.flag(true))
        assertTrue(VenuePlace.flag("TRUE"))
        assertTrue(VenuePlace.flag(1))
        assertFalse(VenuePlace.flag(false))
        assertFalse(VenuePlace.flag("yes"))
        assertFalse(VenuePlace.flag(0))
        assertFalse(VenuePlace.flag(null))
    }
}
