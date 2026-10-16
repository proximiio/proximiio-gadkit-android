package io.proximi.gadkit

import io.proximi.gadkit.visit.StopNearbyNotifier
import io.proximi.gadkit.wristband.TrackingHealth
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Port of ProximiioGADKit `GADSettingsTests`. */
class GadSettingsTest {
    @Test
    fun theConfigurationStartsWithTheDefaults() {
        val configuration = GadConfiguration("https://api.example.com", "token", "https://relay.example.com", "relay")
        assertEquals(GadSettings.STANDARD, configuration.settings)
        assertEquals(110.seconds, GadSettings.STANDARD.trackingLostAfter)
        assertEquals(5.0, GadSettings.STANDARD.stopNearbyMeters)
        assertEquals(setOf(0), GadSettings.STANDARD.levelsWithoutPositioning)
        assertEquals(5.seconds, GadSettings.STANDARD.levelChangeViewDuration)
        assertEquals(5.0, GadSettings.STANDARD.levelChangerReachedMeters)
        assertEquals(1800.seconds, GadSettings.STANDARD.floorWithoutPositioningMaximum)
        assertTrue(GadSettings.STANDARD.postsGeofenceNotifications)
    }

    @Test
    fun theRelayApiCanBeAUrl() {
        val configuration = GadConfiguration("https://api.example.com", "token", "https://relay.example.com", "relay")
        assertEquals("relay.example.com", configuration.relayEndpoint.baseUrl.host)
        assertFailsWith<IllegalArgumentException> { GadConfiguration("https://api.example.com", "token", "file:///tmp", "relay") }
    }

    @Test
    fun trackingAlertsFollowTheSettings() {
        val settings = GadSettings(trackingLostAfter = 30.seconds, trackingStoppedAfter = 60.seconds)
        val since = Instant.ofEpochSecond(0)
        fun health(afterSeconds: Long) = TrackingHealth.evaluate(true, since, since.plusSeconds(afterSeconds), settings)
        assertEquals(TrackingHealth.TRACKING, health(29))
        assertEquals(TrackingHealth.LOST_PLACE, health(30))
        assertEquals(TrackingHealth.STOPPED_RESPONDING, health(60))
        assertEquals(TrackingHealth.TRACKING, TrackingHealth.evaluate(true, since, since.plusSeconds(60)))
    }

    @Test
    fun stopNearbyFollowsTheSettings() {
        val stop = Coordinate(24.5, 54.4)
        val eightMetresNorth = Coordinate(24.5 + 8.0 / 111_320, 54.4)
        assertFalse(StopNearbyNotifier.isNearby(eightMetresNorth, 1.0, stop, 1.0))
        assertTrue(StopNearbyNotifier.isNearby(eightMetresNorth, 1.0, stop, 1.0, nearbyMeters = 10.0))
        assertTrue(StopNearbyNotifier.isNearby(eightMetresNorth, 1.0, stop, 1.0, routeMeters = 9.0, nearbyMeters = 10.0))
    }
}
