package io.proximi.gadkit

import io.proximi.gadkit.GadConfigurationException.Value
import io.proximi.sdk.ProximiioConfiguration
import io.proximi.sdk.blueiot.binding.BlueiotBindingConfiguration
import io.proximi.sdk.blueiot.cloudrelay.BlueiotCloudRelayEndpoint
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `GADConfigurationErrorTests` (iOS 1.71.0). */
class GadConfigurationExceptionTest {
    private fun configuration(
        api: String = "https://api.example.com",
        token: String = "token",
        relay: String = "https://relay.example.com",
        relayToken: String = "relay",
    ) = GadConfiguration(api, token, relay, relayToken)

    @Test
    fun aCompleteConfigurationPasses() {
        configuration().validate()
        GadConfiguration.checkUrl("http://localhost:8080", Value.API_BASE_URL)
    }

    /** Nagarro, 2026-11-22: the API base URL without `https://`. */
    @Test
    fun anApiBaseUrlWithoutTheSchemeHasNoHost() {
        val error = assertFailsWith<GadConfigurationException> { configuration(api = "api.example.com").validate() }
        assertEquals(GadConfigurationException.UrlWithoutHost(Value.API_BASE_URL, "api.example.com"), error)
    }

    @Test
    fun anApiBaseUrlOverHttpIsRefused() {
        val error = assertFailsWith<GadConfigurationException> { configuration(api = "http://api.example.com").validate() }
        assertEquals(GadConfigurationException.UrlNotHttps(Value.API_BASE_URL, "http://api.example.com"), error)
    }

    @Test
    fun aRelayOverHttpIsRefused() {
        val error = assertFailsWith<GadConfigurationException> { configuration(relay = "http://relay.example.com").validate() }
        assertEquals(GadConfigurationException.UrlNotHttps(Value.RELAY_API_BASE_URL, "http://relay.example.com"), error)
    }

    @Test
    fun emptyTokensAndTokensWithWhitespaceAreRefused() {
        assertEquals(
            GadConfigurationException.EmptyToken(Value.APPLICATION_TOKEN),
            assertFailsWith<GadConfigurationException> { configuration(token = "").validate() },
        )
        assertEquals(
            GadConfigurationException.TokenWithWhitespace(Value.APPLICATION_TOKEN),
            assertFailsWith<GadConfigurationException> { configuration(token = "token\n").validate() },
        )
        assertEquals(
            GadConfigurationException.EmptyToken(Value.RELAY_API_APP_TOKEN),
            assertFailsWith<GadConfigurationException> { configuration(relayToken = "").validate() },
        )
    }

    /** The host app's own SDK configuration: the base URL, each fallback URL, the token. */
    @Test
    fun theSdkConfigurationIsChecked() {
        val sdk = ProximiioConfiguration(token = "token", apiBaseURL = URI("https://api.example.com"), fallbackAPIBaseURLs = emptyList())
        GadConfiguration.validate(sdk)
        assertEquals(
            GadConfigurationException.UrlWithoutHost(Value.API_BASE_URL, "api.example.com"),
            assertFailsWith<GadConfigurationException> { GadConfiguration.validate(sdk.copy(apiBaseURL = URI("api.example.com"))) },
        )
        assertEquals(
            GadConfigurationException.UrlNotHttps(Value.FALLBACK_API_BASE_URL, "http://other.example.com"),
            assertFailsWith<GadConfigurationException> {
                GadConfiguration.validate(sdk.copy(fallbackAPIBaseURLs = listOf(URI("http://other.example.com"))))
            },
        )
    }

    @Test
    fun theBindingConfigurationIsChecked() {
        val relay = requireNotNull(BlueiotCloudRelayEndpoint.fromText("https://relay.example.com"))
        GadConfiguration.validate(BlueiotBindingConfiguration(relayURL = relay, appToken = "relay"))
        assertEquals(
            GadConfigurationException.EmptyToken(Value.RELAY_API_APP_TOKEN),
            assertFailsWith<GadConfigurationException> { GadConfiguration.validate(BlueiotBindingConfiguration(relayURL = relay, appToken = "")) },
        )
    }

    /** The message names the value and holds no token. */
    @Test
    fun theMessageNamesTheValue() {
        val message = GadConfigurationException.UrlWithoutHost(Value.API_BASE_URL, "api.example.com").message.orEmpty()
        assertTrue("API base URL" in message)
        assertTrue("api.example.com" in message)
    }
}
