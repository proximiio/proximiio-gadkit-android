package io.proximi.gadkit

import io.proximi.sdk.core.model.BarrierConfiguration
import io.proximi.sdk.service.ProximiioServiceOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Port of guggenheim-ios `AppEnvironmentTests.theSDKReportsVisitorsToTheConfiguredHostOnly`. */
class VenueServiceConfigurationTest {
    private val backend = GadConfiguration(
        apiBaseUrl = "https://api.proximi.fi",
        applicationToken = "token",
        relayApiBaseUrl = "https://relay-api-sandbox.proximi.fi",
        relayApiAppToken = "relay-token",
    )

    @Test
    fun theSDKReportsVisitorsToTheConfiguredHostOnly() {
        val configuration = VenueService.configuration(backend, ProximiioServiceOptions())
        assertTrue(configuration.visitorReportingEnabled)
        // Each geofence enter and exit is sent at once (iOS 0.5.0).
        assertFalse(configuration.eventBatching)
        assertEquals("api.proximi.fi", configuration.apiBaseURL.host)
        assertTrue(configuration.fallbackAPIBaseURLs.isEmpty())
    }

    @Test
    fun theSDKReportsAGeofenceExitAfterTheSettingsDelay() {
        assertEquals(5.0, VenueService.configuration(backend, ProximiioServiceOptions()).geofenceExitDelay)
        val noDelay = backend.copy(settings = GadSettings(geofenceExitDelay = Duration.ZERO))
        assertEquals(0.0, VenueService.configuration(noDelay, ProximiioServiceOptions()).geofenceExitDelay)
    }

    @Test
    fun theSDKWithholdsPositionsInAPrivacyZone() {
        assertTrue(VenueService.configuration(backend, ProximiioServiceOptions()).withholdsPositionsInPrivacyZones)
    }

    @Test
    fun aPrivacyZoneExitComesAfterItsOwnDelay() {
        assertEquals(1.0, VenueService.configuration(backend, ProximiioServiceOptions()).privacyZoneExitDelay)
        val geofenceDelay = backend.copy(settings = GadSettings(privacyZoneExitDelay = null))
        assertNull(VenueService.configuration(geofenceDelay, ProximiioServiceOptions()).privacyZoneExitDelay)
    }

    /** Port of iOS `GADSettingsTests` (1.39.0): the settings' barriers reach the SDK. */
    @Test
    fun thePositionBarriersOfTheSettingsReachTheSDK() {
        val standard = VenueService.configuration(backend, ProximiioServiceOptions())
        assertEquals(BarrierConfiguration(), standard.positioning.barriers)
        assertTrue(standard.positioning.barriers.isEnabled)
        // The relay-only positioning stays: no beacon scanning on the phone.
        assertFalse(standard.positioning.iBeaconScanningEnabled)
        val barriers = BarrierConfiguration(isEnabled = false, maxWalkingSpeed = 3.0, wallMargin = 0.2)
        val changed = backend.copy(settings = GadSettings(positionBarriers = barriers))
        assertEquals(barriers, VenueService.configuration(changed, ProximiioServiceOptions()).positioning.barriers)
    }

    @Test
    fun aRelayApiURLWithoutAHostIsRejected() {
        assertFailsWith<IllegalArgumentException> { backend.copy(relayApiBaseUrl = "https://") }
    }

    @Test
    fun theTokensAreNotInTheDescription() {
        assertTrue("token" !in backend.toString())
        assertEquals(listOf("token", "relay-token"), backend.secrets)
    }
}
