package io.proximi.gadkit

import io.proximi.sdk.service.ProximiioServiceOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun aRelayApiURLWithoutAHostIsRejected() {
        assertFailsWith<IllegalArgumentException> { backend.copy(relayApiBaseUrl = "https://") }
    }

    @Test
    fun theTokensAreNotInTheDescription() {
        assertTrue("token" !in backend.toString())
        assertEquals(listOf("token", "relay-token"), backend.secrets)
    }
}
