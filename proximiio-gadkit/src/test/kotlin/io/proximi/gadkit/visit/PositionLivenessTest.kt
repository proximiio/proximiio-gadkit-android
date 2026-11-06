package io.proximi.gadkit.visit

import io.proximi.gadkit.GadSettings
import io.proximi.gadkit.wristband.TrackingHealth
import io.proximi.map.core.FloorKey
import io.proximi.map.core.Journey as MapJourney
import io.proximi.map.core.JourneyStop as MapJourneyStop
import io.proximi.map.core.MapCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The journey camera without a live position (field tests at the Guggenheim, 2026-11-06; iOS 1.35.0 `PositionLivenessTests`). */
class PositionLivenessTest {
    private val settings = GadSettings.STANDARD

    @Test
    fun aPositionIsLiveWhilePositioningIsAvailable() {
        assertTrue(VisitViewModel.isLive(TrackingHealth.TRACKING, 1.seconds, settings))
        assertTrue(VisitViewModel.isLive(TrackingHealth.TRACKING, 3600.seconds, settings))
        assertTrue(VisitViewModel.isLive(TrackingHealth.PRIVACY_ZONE, 600.seconds, settings))
        assertFalse(VisitViewModel.isLive(TrackingHealth.LOST_PLACE, 1.seconds, settings))
        assertFalse(VisitViewModel.isLive(TrackingHealth.STOPPED_RESPONDING, 1.seconds, settings))
        assertFalse(VisitViewModel.isLive(TrackingHealth.TRACKING, null, settings))
    }

    /**
     * The SDK hands a new map its last position at any age: the position of a wristband that another
     * phone took over 44 min before does not count.
     */
    @Test
    fun beforePositioningOnlyARecentPositionIsLive() {
        assertTrue(VisitViewModel.isLive(TrackingHealth.WAITING, 109.seconds, settings))
        assertFalse(VisitViewModel.isLive(TrackingHealth.WAITING, 110.seconds, settings))
        assertFalse(VisitViewModel.isLive(TrackingHealth.WAITING, 44.minutes, settings))
        assertFalse(VisitViewModel.isLive(TrackingHealth.WAITING, null, settings))
        assertFalse(VisitViewModel.isLive(TrackingHealth.WAITING, 30.seconds, settings.copy(trackingLostAfter = 30.seconds)))
    }

    @Test
    fun withoutAPositionTheCameraShowsTheNextStop() {
        fun stop(id: String, state: MapJourneyStop.State, kind: MapJourneyStop.Kind = MapJourneyStop.Kind.PLANNED) =
            MapJourneyStop(id = id, title = id, coordinate = MapCoordinate(24.54, 54.39), floor = FloorKey(1.0), poiId = id, kind = kind, state = state)
        val journey = MapJourney(
            listOf(
                stop("done", MapJourneyStop.State.DONE),
                stop("skipped", MapJourneyStop.State.SKIPPED),
                stop("next", MapJourneyStop.State.PENDING),
                stop("last", MapJourneyStop.State.PENDING),
            ),
        )
        assertEquals("next", VisitViewModel.stopWithoutPosition(detour = null, active = null, journey = journey)?.id)
        val active = stop("active", MapJourneyStop.State.ACTIVE)
        assertEquals("active", VisitViewModel.stopWithoutPosition(detour = null, active = active, journey = journey)?.id)
        val cafe = stop("cafe", MapJourneyStop.State.ACTIVE, MapJourneyStop.Kind.DETOUR)
        assertEquals("cafe", VisitViewModel.stopWithoutPosition(detour = cafe, active = active, journey = journey)?.id)
        val finished = MapJourney(listOf(stop("done", MapJourneyStop.State.DONE), stop("skipped", MapJourneyStop.State.SKIPPED)))
        assertNull(VisitViewModel.stopWithoutPosition(detour = null, active = null, journey = finished))
    }
}
