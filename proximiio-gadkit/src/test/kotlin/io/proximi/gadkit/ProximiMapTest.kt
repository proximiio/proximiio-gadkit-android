package io.proximi.gadkit

import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.Journey
import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.StopKind
import io.proximi.gadkit.visit.StopState
import io.proximi.gadkit.visit.VisitEvent
import io.proximi.gadkit.visit.VisitMeta
import io.proximi.gadkit.visit.toMap
import io.proximi.gadkit.wristband.BindingEndReason
import io.proximi.gadkit.wristband.BindingLink
import io.proximi.gadkit.wristband.BindingState
import io.proximi.gadkit.wristband.TrackingHealth
import io.proximi.map.core.JourneyEvent
import io.proximi.map.core.JourneyLegRequest
import io.proximi.sdk.core.model.JsonValue
import io.proximi.sdk.core.model.ProximiioGeofence
import io.proximi.sdk.geofencing.GeofenceEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Port of ProximiioGADKit `ProximiMapTests`. */
class ProximiMapTest {
    private val places = listOf(
        VenuePlace(id = "poi-a", title = "Cloud Canyons", coordinate = Coordinate(24.5, 54.4), level = 1.0, remoteId = "GAD.2010.00033", isArtwork = true),
        VenuePlace(id = "poi-b", title = "Tongari-kun", coordinate = Coordinate(24.5001, 54.4001), level = 3.0, remoteId = "GAD.2018.00034", isArtwork = true),
        VenuePlace(id = "poi-cafe", title = "Café", coordinate = Coordinate(24.5002, 54.4), level = 0.0),
    )

    private fun stop(id: String, kind: StopKind = StopKind.PLANNED, state: StopState = StopState.PENDING) =
        JourneyStop(id = id, title = id, coordinate = Coordinate(24.5, 54.4), level = 1.0, poiId = id, state = state, kind = kind)

    private fun standard(stopCount: Int, kind: JourneyKind) = VisitMeta(title = "Your journey", summary = "$stopCount stops", kind = kind)

    @Test
    fun artworksMatchACollectionIdOrAPoiIdAndKeepTheirOrder() {
        val ids = ProximiMap.poiIds(listOf("GAD.2018.00034", "poi-a", "GAD.0000.00000", "poi-cafe"), places)
        assertEquals(listOf("poi-b", "poi-a", "poi-cafe"), ids)
        assertTrue(ProximiMap.poiIds(listOf("unknown"), places).isEmpty())
    }

    @Test
    fun summaryCountsArtworksNotDetours() {
        assertNull(ProximiMap.summary(null, null, ::standard))
        val journey = Journey(
            listOf(
                stop("poi-a", state = StopState.DONE),
                stop("poi-b"),
                stop("poi-cafe", kind = StopKind.DETOUR, state = StopState.ACTIVE),
            ),
        )
        val meta = VisitMeta(title = "Highlights Tour", summary = "2 stops", kind = JourneyKind.CURATED)
        assertEquals(
            JourneySummary(title = "Highlights Tour", summary = "2 stops", kind = JourneyKind.CURATED, stopCount = 2, remainingStopCount = 1, isStarted = true),
            ProximiMap.summary(journey, meta, ::standard),
        )
        val fresh = ProximiMap.summary(Journey(listOf(stop("poi-a"))), null, ::standard)
        assertEquals(JourneyKind.PERSONALISED, fresh?.kind)
        assertEquals(false, fresh?.isStarted)
        assertEquals(1, fresh?.remainingStopCount)
        assertEquals("1 stops", fresh?.summary)
    }

    @Test
    fun wristbandStatusFollowsTheBindingState() {
        val key = { text: UiText -> (text as UiText.Resource).key }
        assertEquals(WristbandStatus.NotConnected(notice = null), ProximiMap.wristbandStatus(BindingState.Unbound, key))
        assertEquals(WristbandStatus.Connecting, ProximiMap.wristbandStatus(BindingState.Binding, key))
        assertEquals(WristbandStatus.Connected, ProximiMap.wristbandStatus(BindingState.Active(BindingLink.RECONNECTING, signalLost = true), key))
        // "Your wristband was scanned by another phone. …"
        assertEquals(
            WristbandStatus.NotConnected(notice = "wristband_notice_superseded"),
            ProximiMap.wristbandStatus(BindingState.Ended(BindingEndReason.Superseded), key),
        )
    }

    @Test
    fun visitEventsBecomeHostEventsWithTheArtworkId() {
        assertEquals(
            ProximiMapEvent.ArtworkReached(ProximiStop(id = "poi-a", title = "poi-a", artworkId = "GAD.2010.00033", level = 1)),
            ProximiMap.event(VisitEvent.ArtworkReached(stop("poi-a")), places),
        )
        assertEquals(
            ProximiMapEvent.DetourStarted(ProximiStop(id = "poi-cafe", title = "Café", artworkId = null, level = 0)),
            ProximiMap.event(VisitEvent.DetourStarted(places[2]), places),
        )
        assertEquals(ProximiMapEvent.JourneyFinished, ProximiMap.event(VisitEvent.Journey(JourneyEvent.JourneyFinished), places))
        assertEquals(ProximiMapEvent.OffRoute(300.seconds), ProximiMap.event(VisitEvent.Journey(JourneyEvent.OffRouteTooLong(duration = 300.0)), places))
        assertEquals(
            ProximiMapEvent.NextStop(ProximiStop(id = "poi-b", title = "poi-b", artworkId = "GAD.2018.00034", level = 1)),
            ProximiMap.event(VisitEvent.Journey(JourneyEvent.LegChanged(to = stop("poi-b").toMap(), reason = JourneyLegRequest.Reason.STARTED)), places),
        )
        assertEquals(ProximiMapEvent.BackOnRoute, ProximiMap.event(VisitEvent.Journey(JourneyEvent.ReturnedToRoute), places))
        assertEquals(
            ProximiMapEvent.DetourEnded(ProximiStop(id = "poi-cafe", title = "poi-cafe", level = 1), completed = true),
            ProximiMap.event(VisitEvent.Journey(JourneyEvent.DetourEnded(stop("poi-cafe", kind = StopKind.DETOUR).toMap(), completed = true)), places),
        )
        // The skip comes from the visitor's confirmation; the navigator's copy is not repeated.
        assertNull(ProximiMap.event(VisitEvent.Journey(JourneyEvent.StopSkipped(stop("poi-a").toMap())), places))
        assertEquals(ProximiMapEvent.TrackingChanged(TrackingHealth.LOST_PLACE), ProximiMap.event(VisitEvent.TrackingChanged(TrackingHealth.LOST_PLACE), places))
    }

    @Test
    fun geofenceEventsNameTheArtwork() {
        val artwork = ProximiioGeofence(
            id = "g1",
            name = "Cloud Canyons",
            metadata = JsonValue.Object(mapOf("geofence_type" to JsonValue.String("artwork"))),
            remoteId = "GAD.2010.00033",
        )
        assertEquals(
            ProximiMapEvent.GeofenceEntered(ProximiGeofence(id = "g1", name = "Cloud Canyons", type = "artwork", artworkId = "GAD.2010.00033")),
            ProximiMap.event(GeofenceEvent.Entered(artwork)),
        )
        val gallery = ProximiioGeofence(
            id = "g2",
            name = "G403 Weaving",
            metadata = JsonValue.Object(mapOf("geofence_type" to JsonValue.String("gallery"))),
            remoteId = "G403",
        )
        assertEquals(
            ProximiMapEvent.GeofenceExited(ProximiGeofence(id = "g2", name = "G403 Weaving", type = "gallery", artworkId = null)),
            ProximiMap.event(GeofenceEvent.Exited(gallery, dwellTime = 12.0)),
        )
    }

    @Test
    fun arrivalCountsAFixNearAnyPlaceOfEitherVenue() {
        val guggenheim = Coordinate(24.5, 54.4)
        // About 11 km east, like the DCT office.
        val office = Coordinate(24.5, 54.5)
        assertTrue(ArrivalDetector.isNear(Coordinate(24.5, 54.501), listOf(guggenheim, office)))
        assertFalse(ArrivalDetector.isNear(Coordinate(24.5, 54.45), listOf(guggenheim, office)))
    }

    @Test
    fun nothingOpensBeforeTheStart() {
        for (status in listOf(ProximiMap.Status.Idle, ProximiMap.Status.Starting)) {
            ProximiMap.Screen.entries.forEach { assertFalse(ProximiMap.canOpen(it, status, hasJourney = true)) }
        }
    }

    @Test
    fun journeyScreensOpenOnlyWithAStoredJourney() {
        val ready = ProximiMap.Status.Ready
        assertTrue(ProximiMap.canOpen(ProximiMap.Screen.MUSEUM_MAP, ready, hasJourney = false))
        assertTrue(ProximiMap.canOpen(ProximiMap.Screen.PLAN_JOURNEY, ready, hasJourney = false))
        for (screen in listOf(ProximiMap.Screen.JOURNEY_READY, ProximiMap.Screen.JOURNEY, ProximiMap.Screen.MANAGE_JOURNEY)) {
            assertFalse(ProximiMap.canOpen(screen, ready, hasJourney = false))
            assertTrue(ProximiMap.canOpen(screen, ready, hasJourney = true))
        }
    }
}
