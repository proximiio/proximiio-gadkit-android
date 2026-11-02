package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.StopKind
import io.proximi.gadkit.visit.StopState
import io.proximi.gadkit.visit.VisitPlan
import io.proximi.sdk.VenueDataKind
import io.proximi.sdk.core.model.JsonValue
import io.proximi.sdk.core.model.ProximiioFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `VenueDataTests`. */
class VenueDataTest {
    private val places = listOf(
        VenuePlace(id = "a", title = "Artwork A", coordinate = Coordinate(24.5, 54.4), level = 1.0),
        VenuePlace(id = "b", title = "Artwork B", coordinate = Coordinate(24.6, 54.5), level = 1.0),
        VenuePlace(id = "c", title = "Artwork C", coordinate = Coordinate(24.7, 54.6), level = 3.0),
    )

    private fun journey(states: Map<String, StopState> = emptyMap()): Journey {
        val journey = VisitPlan.journey(listOf("a", "b", "c"), places)
        return Journey(journey.stops.map { it.copy(state = states[it.id] ?: StopState.PENDING) })
    }

    @Test
    fun aStopWhosePlaceIsDeletedIsRemovedUnlessItIsDone() {
        val stored = journey(mapOf("a" to StopState.DONE, "b" to StopState.ACTIVE))
        val remaining = listOf(places[2])
        val reconciled = VisitPlan.reconciled(stored, remaining)
        assertEquals(listOf("a", "c"), reconciled.stops.map { it.id })
        assertEquals(stored.stops[0], reconciled.stops[0])
        assertEquals(listOf("b"), VisitPlan.stopsWithoutPlace(stored, remaining).map { it.id })
    }

    @Test
    fun aMovedPlaceMovesItsStopAndKeepsTheState() {
        val stored = journey(mapOf("a" to StopState.DONE, "b" to StopState.SKIPPED, "c" to StopState.ACTIVE))
        val moved = listOf(
            VenuePlace(id = "a", title = "Artwork A (new)", coordinate = Coordinate(25.0, 55.0), level = 2.0),
            VenuePlace(id = "b", title = "Artwork B (new)", coordinate = Coordinate(25.0, 55.0), level = 2.0),
            VenuePlace(id = "c", title = "Artwork C (new)", coordinate = Coordinate(25.1, 55.1), level = 4.0),
        )
        val reconciled = VisitPlan.reconciled(stored, moved)
        assertEquals(listOf(StopState.DONE, StopState.SKIPPED, StopState.ACTIVE), reconciled.stops.map { it.state })
        // A done stop keeps the title and position the visitor saw.
        assertEquals("Artwork A", reconciled.stops[0].title)
        assertEquals("Artwork B (new)", reconciled.stops[1].title)
        assertEquals(Coordinate(25.1, 55.1), reconciled.stops[2].coordinate)
        assertEquals(4.0, reconciled.stops[2].level)
    }

    @Test
    fun unchangedPlacesLeaveTheJourneyAsItIs() {
        val stored = journey(mapOf("a" to StopState.DONE, "b" to StopState.ACTIVE))
        assertEquals(stored, VisitPlan.reconciled(stored, places))
        assertTrue(VisitPlan.stopsWithoutPlace(stored, places).isEmpty())
    }

    @Test
    fun aDetourStopIsKept() {
        val cafe = JourneyStop(
            id = "cafe", title = "Café", coordinate = Coordinate(24.5, 54.4), level = null, poiId = "cafe",
            state = StopState.ACTIVE, kind = StopKind.DETOUR,
        )
        val stored = Journey(journey().stops + cafe)
        assertEquals("cafe", VisitPlan.reconciled(stored, places).stops.last().id)
    }

    /**
     * Nagarro, 2026-11-02: the language changed wrongly while the app ran. The stops kept the POI
     * titles of the language the app launched in (iOS 1.31.3).
     */
    @Test
    fun everyStopTakesItsPlacesTitleInTheAppsLanguage() {
        val cafe = JourneyStop(
            id = "cafe", title = "Café", coordinate = Coordinate(24.5, 54.4), level = null, poiId = "cafe",
            state = StopState.ACTIVE, kind = StopKind.DETOUR,
        )
        val stored = Journey(journey(mapOf("a" to StopState.DONE, "b" to StopState.ACTIVE)).stops + cafe)
        fun arabic(id: String, title: String, english: String) =
            VenuePlace(id = id, title = title, titles = setOf(english, title), coordinate = Coordinate(24.5, 54.4), level = 1.0)
        val arabicPlaces = listOf(
            arabic("a", "العمل أ", "Artwork A"),
            arabic("b", "العمل ب", "Artwork B"),
            arabic("c", "العمل ج", "Artwork C"),
            arabic("cafe", "المقهى", "Café"),
        )
        val retitled = VisitPlan.retitled(stored, arabicPlaces)
        assertEquals(listOf("العمل أ", "العمل ب", "العمل ج", "المقهى"), retitled.stops.map { it.title })
        assertEquals(stored.stops.map { it.state }, retitled.stops.map { it.state })
        assertEquals(stored.stops.map { it.coordinate }, retitled.stops.map { it.coordinate })
        assertEquals(retitled, VisitPlan.retitled(retitled, arabicPlaces))
    }

    /** A done stop keeps the title the visitor saw after the POI was renamed ([VisitPlan.reconciled]). */
    @Test
    fun aStopWithATitleThePlaceNoLongerHasKeepsIt() {
        val stored = journey(mapOf("a" to StopState.DONE))
        val renamed = VenuePlace(id = "a", title = "Artwork A (new)", titles = setOf("Artwork A (new)"), coordinate = Coordinate(24.5, 54.4), level = 1.0)
        assertEquals(stored, VisitPlan.retitled(stored, listOf(renamed)))
    }

    @Test
    fun aPlaceHasTheTitlesOfEveryLanguage() {
        val i18n = mapOf("en" to "Artwork A", "ar" to "العمل أ", "fr" to "")
        val feature = VenueFeature(
            id = "a",
            type = "poi",
            geometry = Geometry("Point", listOf(54.4, 24.5)),
            properties = mapOf("title" to "Artwork A", "title_i18n" to i18n),
            level = 1.0,
        )
        val arabic = assertNotNull(VenuePlace.from(feature, language = "ar"))
        assertEquals(setOf("Artwork A", "العمل أ"), arabic.titles)
        assertEquals("العمل أ", arabic.title)
        assertEquals("Artwork A", assertNotNull(VenuePlace.from(feature, language = "en")).title)
    }

    @Test
    fun featureChangesAreCountedById() {
        fun feature(id: String, title: String) = ProximiioFeature(
            id = id,
            geometry = ProximiioFeature.Geometry("Point", JsonValue.Array(listOf(JsonValue.Number(54.4), JsonValue.Number(24.5)))),
            properties = JsonValue.Object(mapOf("title" to JsonValue.String(title))),
        )
        val old = listOf(feature("1", "One"), feature("2", "Two"), feature("3", "Three"))
        val new = listOf(feature("3", "Three"), feature("2", "Two, moved"), feature("4", "Four"))
        assertEquals(VenueDataRefresh.Changes(added = 1, removed = 1, changed = 1), VenueDataRefresh.changes(old, new))
        assertTrue(VenueDataRefresh.changes(old, old.reversed()).isEmpty)
    }

    /**
     * The open map redraws a changed style itself. The screens read the amenity catalogue for the icons
     * and lists, so a change of it reads it again (iOS 1.23.0).
     */
    @Test
    fun theScreensFollowFeaturesGeofencesFloorsPlacesAndAmenities() {
        assertEquals(
            setOf(VenueDataKind.features, VenueDataKind.geofences, VenueDataKind.floors, VenueDataKind.places, VenueDataKind.amenities),
            VenueDataRefresh.WATCHED_KINDS,
        )
    }
}
