package io.proximi.gadkit

import io.proximi.sdk.ProximiioConfiguration
import io.proximi.sdk.blueiot.binding.BlueiotBindingConfiguration
import io.proximi.sdk.blueiot.cloudrelay.BlueiotCloudRelayEndpoint
import java.net.URI
import java.util.Locale

/**
 * The backend of the host app's build: the Proximi.io API and the relay-api, as iOS
 * `GADConfiguration`. The host app passes it to [ProximiMap]. ProximiioGADKit sets no fallback
 * host, so it contacts only these two hosts.
 *
 * @throws IllegalArgumentException when [relayApiBaseUrl] has no host, or a scheme other than
 *   `http`, `https`, `ws` or `wss`.
 */
public data class GadConfiguration(
    /** Proximi.io API base URL. Passed to the SDK as `apiBaseURL`. */
    public val apiBaseUrl: String,
    /** Proximi.io application token of the Guggenheim organisation. */
    public val applicationToken: String,
    /** relay-api base URL: wristband binding and wristband positions. Scheme and host only; the SDK drops any path. */
    public val relayApiBaseUrl: String,
    /** relay-api app token of the site. The SDK sends it on `GET /v1/meta` and `POST /v1/bindings`. */
    public val relayApiAppToken: String,
    /** Times and distances of the screens, and the geofence notifications. */
    public val settings: GadSettings = GadSettings.STANDARD,
) {
    init {
        require(BlueiotCloudRelayEndpoint.fromText(relayApiBaseUrl) != null) { "relayApiBaseUrl is not a relay-api URL: $relayApiBaseUrl" }
    }

    /** The tokens, for [FieldLog.start]. */
    public val secrets: List<String> get() = listOf(applicationToken, relayApiAppToken)

    internal val relayEndpoint: BlueiotCloudRelayEndpoint get() = requireNotNull(BlueiotCloudRelayEndpoint.fromText(relayApiBaseUrl))

    /**
     * Throws the first value [ProximiMap] cannot start with: the API base URL, the application
     * token, the relay-api base URL, the relay-api app token. [ProximiMap.start] runs it before the
     * SDK starts. iOS `GADConfiguration.validate()` (1.71.0).
     *
     * @throws GadConfigurationException
     */
    public fun validate() {
        checkUrl(apiBaseUrl, GadConfigurationException.Value.API_BASE_URL)
        checkToken(applicationToken, GadConfigurationException.Value.APPLICATION_TOKEN)
        checkUrl(relayEndpoint.baseUrl.toString(), GadConfigurationException.Value.RELAY_API_BASE_URL)
        checkToken(relayApiAppToken, GadConfigurationException.Value.RELAY_API_APP_TOKEN)
    }

    override fun toString(): String = "GadConfiguration(apiBaseUrl=$apiBaseUrl, relayApiBaseUrl=$relayApiBaseUrl)"

    public companion object {
        /**
         * Throws the first value of [configuration] the SDK cannot start with: the API base URL, each
         * fallback API base URL, the token. Call it before you create `Proximiio`.
         *
         * @throws GadConfigurationException
         */
        public fun validate(configuration: ProximiioConfiguration) {
            checkUrl(configuration.apiBaseURL.toString(), GadConfigurationException.Value.API_BASE_URL)
            for (url in configuration.fallbackAPIBaseURLs) checkUrl(url.toString(), GadConfigurationException.Value.FALLBACK_API_BASE_URL)
            checkToken(configuration.token, GadConfigurationException.Value.APPLICATION_TOKEN)
        }

        /**
         * Throws the first value of [configuration] the wristband binding cannot work with: the
         * relay-api base URL, the relay-api app token. Call it before you create
         * `BlueiotWristbandBinding`.
         *
         * @throws GadConfigurationException
         */
        public fun validate(configuration: BlueiotBindingConfiguration) {
            checkUrl(configuration.relayURL.baseUrl.toString(), GadConfigurationException.Value.RELAY_API_BASE_URL)
            checkToken(configuration.appToken, GadConfigurationException.Value.RELAY_API_APP_TOKEN)
        }

        /**
         * A host, and https; http for localhost only (the SDK's rule for a base URL). Pure; covered
         * by tests.
         */
        internal fun checkUrl(text: String, value: GadConfigurationException.Value) {
            val url = runCatching { URI(text.trim()) }.getOrNull()
            if (url == null || url.host.isNullOrEmpty()) throw GadConfigurationException.UrlWithoutHost(value, text)
            val isSecure = when (url.scheme?.lowercase(Locale.ROOT)) {
                "https" -> true
                "http" -> url.host.lowercase(Locale.ROOT) in LOCAL_HOSTS
                else -> false
            }
            if (!isSecure) throw GadConfigurationException.UrlNotHttps(value, text)
        }

        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        /** Not empty and without spaces or line breaks. Pure; covered by tests. */
        internal fun checkToken(token: String, value: GadConfigurationException.Value) {
            if (token.isEmpty()) throw GadConfigurationException.EmptyToken(value)
            if (token.any { it.isWhitespace() }) throw GadConfigurationException.TokenWithWhitespace(value)
        }
    }
}

/**
 * A backend value the SDK cannot start with. The message names the value and the fault, in English,
 * for the developer; it holds no token. iOS `GADConfigurationError` (1.71.0; Nagarro, 2026-11-22: an
 * API base URL without `https://`).
 */
public sealed class GadConfigurationException(message: String) : IllegalArgumentException(message) {
    /** A value of the configuration: [text] is its name in the message. */
    public enum class Value(public val text: String) {
        API_BASE_URL("API base URL"),
        FALLBACK_API_BASE_URL("fallback API base URL"),
        RELAY_API_BASE_URL("relay-api base URL"),
        APPLICATION_TOKEN("application token"),
        RELAY_API_APP_TOKEN("relay-api app token"),
    }

    /** The URL has no host, for example `api.example.com` without `https://`. */
    public data class UrlWithoutHost(val value: Value, val url: String) :
        GadConfigurationException("The ${value.text} \"$url\" has no host. Write it with the scheme, for example https://api.example.com.")

    /** The URL is not https. http is accepted for localhost only. */
    public data class UrlNotHttps(val value: Value, val url: String) :
        GadConfigurationException("The ${value.text} \"$url\" is not an https URL.")

    /** The token is empty. */
    public data class EmptyToken(val value: Value) : GadConfigurationException("The ${value.text} is empty.")

    /** The token holds a space or a line break, for example from a copied line. */
    public data class TokenWithWhitespace(val value: Value) : GadConfigurationException("The ${value.text} holds a space or a line break.")
}
