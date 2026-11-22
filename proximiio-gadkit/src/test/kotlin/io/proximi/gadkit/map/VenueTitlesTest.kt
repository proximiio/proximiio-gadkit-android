package io.proximi.gadkit.map

import io.proximi.sdk.core.model.ProximiioAmenity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `VenueTitlesTests` (1.70.0). */
class VenueTitlesTest {
    /** The untagged `title` is English: it names the kind in English and not in Arabic. */
    @Test
    fun theUntaggedTitleCountsOnlyForTheDataLanguage() {
        val toilet = ProximiioAmenity(id = "a-toilet", title = "Accessible Toilet")
        val titles = VenueTitles.titles(toilet, untaggedLanguage = "en")
        assertEquals("Accessible Toilet", VenueTitles.title(titles, "en"))
        assertEquals("Accessible Toilet", VenueTitles.title(titles, "en-GB"))
        assertNull(VenueTitles.title(titles, "ar"))
        assertTrue(VenueTitles.titles(toilet, untaggedLanguage = "").isEmpty())
    }

    /** `title_i18n` names the kind in each of its languages and goes before the untagged title. */
    @Test
    fun theTranslatedTitleGoesBeforeTheUntaggedTitle() {
        val toilet = ProximiioAmenity(
            id = "a-toilet", title = "Accessible Toilet", category = null, icon = null, details = null, metadata = null,
            titleTranslations = mapOf("AR" to "دورة مياه", "en" to "Accessible toilet", "fr" to "  "),
        )
        val titles = VenueTitles.titles(toilet, untaggedLanguage = "en")
        assertEquals("دورة مياه", VenueTitles.title(titles, "ar"))
        assertEquals("دورة مياه", VenueTitles.title(titles, "ar-AE"))
        assertEquals("Accessible toilet", VenueTitles.title(titles, "en"))
        assertNull(VenueTitles.title(titles, "fr"))
    }

    /**
     * A kind takes the titles of its one amenity with a place. Two amenities of a kind leave the
     * library's text; so does an amenity no place uses.
     */
    @Test
    fun aKindTakesTheTitlesOfItsOnlyUsedAmenity() {
        val amenities = listOf(
            ProximiioAmenity(id = "a-restaurant", title = "Restaurant"),
            ProximiioAmenity(id = "a-women", title = "Women's Restroom"),
            ProximiioAmenity(id = "a-men", title = "Men's Restroom"),
            ProximiioAmenity(id = "a-prayer", title = "Prayer Room"),
            ProximiioAmenity(id = "a-learning", title = "Learning & Engagement"),
        )
        val titles = VenueTitles.titles(amenities, used = setOf("a-restaurant", "a-women", "a-men", "a-learning"), untaggedLanguage = "en")
        assertEquals(mapOf(AmenityKind.RESTAURANT to mapOf("en" to "Restaurant")), titles)
    }

    /** The kind's title is the data title in the app language, else the library's text. */
    @Test
    fun aKindWithoutADataTitleShowsTheLibrarysText() {
        val toilet = ProximiioAmenity(id = "a-toilet", title = "Accessible Toilet")
        VenueTitles.update(listOf(toilet), setOf("a-toilet"), enabled = true, untaggedLanguage = "en")
        assertEquals("Accessible Toilet", VenueTitles.title(AmenityKind.RESTROOM, "en"))
        assertNull(VenueTitles.title(AmenityKind.RESTROOM, "ar"))
        assertNull(VenueTitles.title(AmenityKind.CAFE, "en"))
        VenueTitles.update(listOf(toilet), setOf("a-toilet"), enabled = false, untaggedLanguage = "en")
        assertNull(VenueTitles.title(AmenityKind.RESTROOM, "en"))
    }
}
