package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Port of guggenheim-ios `VenuePlaceLocalizationTests` (in VisitPlanTests.swift). */
class VenuePlaceLocalizationTest {
    private val i18n = mapOf("en" to "Café", "ar" to "المقهى", "fr" to "")

    @Test
    fun picksTheAppLanguageAndFallsBackToTheBaseLanguage() {
        assertEquals("المقهى", VenuePlace.localized(i18n, "ar"))
        assertEquals("Café", VenuePlace.localized(i18n, "en-GB"))
    }

    @Test
    fun returnsNilForMissingOrEmptyTranslations() {
        assertNull(VenuePlace.localized(i18n, "fr"))
        assertNull(VenuePlace.localized(i18n, "de"))
        assertNull(VenuePlace.localized("Café", "en"))
        assertNull(VenuePlace.localized(null, "en"))
    }
}

/** Not in iOS: the feature rules of `VenuePlace.init(feature:)`. */
class VenuePlaceFeatureTest {
    private fun poi(properties: Map<String, Any?>, type: String? = "poi", geometry: Geometry? = Geometry("Point", listOf(54.4, 24.5))) =
        VenueFeature(id = "p", type = type, geometry = geometry, properties = properties, level = 2.0)

    @Test
    fun pointPOIsBecomePlacesWithLocalizedTitleAndFallbacks() {
        val place = VenuePlace.from(
            poi(
                mapOf(
                    "title_i18n" to mapOf("en" to "Café"),
                    "title" to "Cafe",
                    "description_i18n" to mapOf("en" to ""),
                    "description" to "Coffee",
                    "amenity" to "food:cafe",
                    "images" to listOf("https://example.com/a.jpg", "https://example.com/b.jpg"),
                ),
            ),
            language = "en",
        )!!
        assertEquals("Café", place.title)
        assertEquals("Coffee", place.details)
        assertEquals(Coordinate(24.5, 54.4), place.coordinate)
        assertEquals(2.0, place.level)
        assertEquals("food:cafe", place.amenityId)
        assertEquals("https://example.com/a.jpg", place.imageUrl)
        assertEquals("Name", VenuePlace.from(poi(mapOf("title" to "", "name" to "Name")), "en")?.title)
        assertEquals("p", VenuePlace.from(poi(emptyMap()), "en")?.title)
        assertNull(VenuePlace.from(poi(mapOf("images" to listOf("relative.jpg"))), "en")?.imageUrl)
        assertEquals("GAD.2010.00033", VenuePlace.from(poi(mapOf("remote_id" to "GAD.2010.00033")), "en")?.remoteId)
        assertNull(VenuePlace.from(poi(emptyMap()), "en")?.remoteId)
        assertEquals(true, VenuePlace.from(poi(mapOf("metadata" to mapOf("artwork_id" to "a1"))), "en")?.isArtwork)
        assertEquals(false, VenuePlace.from(poi(mapOf("metadata" to mapOf("type" to "gallery"))), "en")?.isArtwork)
        assertEquals(true, VenuePlace.from(poi(mapOf("metadata" to mapOf("gallery_id" to "9c8403c6"), "remote_id" to "GAD.2018.00034")), "en")?.isArtwork)
        assertEquals(false, VenuePlace.from(poi(mapOf("remote_id" to "9c8403c6-2898-4259-8c6a-dc3b22d93d20")), "en")?.isArtwork)
        assertEquals(true, VenuePlace.from(poi(mapOf("amenity" to VenuePlace.ARTWORK_AMENITY_ID)), "en")?.isArtwork)
        assertNull(VenuePlace.from(poi(emptyMap(), type = "gallery"), "en"))
        assertNull(VenuePlace.from(poi(emptyMap(), geometry = Geometry("Polygon", listOf(listOf(listOf(54.4, 24.5))))), "en"))
    }

    @Test
    fun allIsSortedByTitleWithNumbersInOrder() {
        fun feature(id: String, title: String) = VenueFeature(id, "poi", Geometry("Point", listOf(0.0, 0.0)), mapOf("title" to title), 0.0)
        val places = VenuePlace.all(listOf(feature("1", "Gallery 10"), feature("2", "gallery 2"), feature("3", "Café")), "en")
        assertEquals(listOf("Café", "gallery 2", "Gallery 10"), places.map { it.title })
    }

    @Test
    fun matchingIgnoresCaseAndDiacritics() {
        val places = listOf(VenuePlace(id = "c", title = "Café", coordinate = Coordinate(0.0, 0.0), level = 0.0))
        assertEquals(listOf("c"), VenuePlace.matching(" CAFE ", places).map { it.id })
        assertEquals(places, VenuePlace.matching("", places))
        assertEquals(emptyList(), VenuePlace.matching("shop", places))
    }
}
