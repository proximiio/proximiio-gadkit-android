package io.proximi.gadkit

import io.proximi.sdk.blueiot.cloudrelay.BlueiotCloudRelayEndpoint

/**
 * The backend of the host app's build: the Proximi.io API and the relay-api, as iOS
 * `GADConfiguration`. The host app passes it to `WristbandSession` and [VenueService.start].
 * ProximiioGADKit sets no fallback host, so it contacts only these two hosts.
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
) {
    init {
        require(BlueiotCloudRelayEndpoint.fromText(relayApiBaseUrl) != null) { "relayApiBaseUrl is not a relay-api URL: $relayApiBaseUrl" }
    }

    /** The tokens, for [FieldLog.start]. */
    public val secrets: List<String> get() = listOf(applicationToken, relayApiAppToken)

    internal val relayEndpoint: BlueiotCloudRelayEndpoint get() = requireNotNull(BlueiotCloudRelayEndpoint.fromText(relayApiBaseUrl))

    override fun toString(): String = "GadConfiguration(apiBaseUrl=$apiBaseUrl, relayApiBaseUrl=$relayApiBaseUrl)"
}
