package org.octopusden.octopus.components.registry.cli.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.octopusden.octopus.components.registry.cli.client.HttpExchange
import org.octopusden.octopus.components.registry.cli.client.JdkHttpExchange
import org.octopusden.octopus.components.registry.cli.client.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets

/**
 * Response to the device-authorization request (RFC 8628 section 3.2). `interval` defaults to 5s per
 * the spec; `verificationUriComplete` is optional (offered by some providers for QR-code shortcuts).
 */
@Serializable
data class DeviceAuthorizationResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    val interval: Int = 5,
)

/**
 * A successful token response (RFC 6749 section 5.1). `refreshToken` is present only when the
 * `offline_access` scope was granted; `idToken` is present for OIDC.
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("token_type") val tokenType: String,
    @SerialName("id_token") val idToken: String? = null,
)

/**
 * An OAuth error response (RFC 6749 section 5.2 / RFC 8628 section 3.5). During device-flow polling
 * the `error` field carries the state-machine signals: `authorization_pending`, `slow_down`,
 * `expired_token`, `access_denied`.
 */
@Serializable
data class TokenErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
)

/** Functional seam over sleeping so the polling loop runs instantly under test. */
fun interface Sleeper {
    fun sleep(millis: Long)
}

/** Default [Sleeper] that really blocks the current thread. */
class ThreadSleeper : Sleeper {
    override fun sleep(millis: Long) = Thread.sleep(millis)
}

/** Thrown when the device-flow grant cannot complete (expired, denied, or a hard transport error). */
class DeviceFlowException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * OIDC Device Authorization Grant (RFC 8628) client over the injectable [HttpExchange] seam.
 *
 * Endpoint paths follow the standard Keycloak realm layout under `<issuer>`:
 *   - device authorization: `<issuer>/protocol/openid-connect/auth/device`
 *   - token:                `<issuer>/protocol/openid-connect/token`
 *   - revocation:           `<issuer>/protocol/openid-connect/revoke`
 *
 * The exact paths + the public client id are confirmed by the Keycloak Part C spike; see the
 * TODO(spike) markers below.
 */
class DeviceFlowClient(
    private val exchange: HttpExchange = JdkHttpExchange(),
    private val sleeper: Sleeper = ThreadSleeper(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Starts the grant: POSTs `client_id` + `scope` to the device-authorization endpoint and returns
     * the parsed [DeviceAuthorizationResponse]. [scope] defaults to "openid"; callers append
     * "offline_access" (via [scopeFor]) when a refresh token is wanted.
     */
    fun requestDeviceAuthorization(
        issuer: String,
        clientId: String,
        scope: String = "openid",
    ): DeviceAuthorizationResponse {
        // TODO(spike): confirm device_authorization endpoint path + client_id against Keycloak Part C
        val form = formBody(
            "client_id" to clientId,
            "scope" to scope,
        )
        val request = postForm("${issuerBase(issuer)}/protocol/openid-connect/auth/device", form)
        val response = exchange.send(request)
        if (response.statusCode() !in 200..299) {
            throw DeviceFlowException(
                "Device authorization request failed: HTTP ${response.statusCode()} ${errorDetail(response.body())}",
            )
        }
        return decode(response.body(), DeviceAuthorizationResponse.serializer(), "device authorization response")
    }

    /**
     * Polls the token endpoint until the user completes authorization, implementing the RFC 8628
     * section 3.5 state machine:
     *   - `authorization_pending` -> wait [interval] seconds and retry.
     *   - `slow_down`             -> increase the interval by 5s, then wait and retry.
     *   - `expired_token`         -> abort with a clear error.
     *   - `access_denied`         -> abort.
     *   - 2xx success             -> return the [TokenResponse].
     *
     * Total wait is capped: once [expiresInSeconds] of wall-clock time has elapsed the loop aborts
     * even if the server keeps replying `authorization_pending`.
     */
    fun pollToken(
        issuer: String,
        clientId: String,
        deviceCode: String,
        interval: Int,
        expiresInSeconds: Long,
    ): TokenResponse {
        val deadline = nowMillis() + expiresInSeconds * 1000L
        var currentInterval = if (interval > 0) interval else 5
        val tokenUri = "${issuerBase(issuer)}/protocol/openid-connect/token"
        while (true) {
            if (nowMillis() >= deadline) {
                throw DeviceFlowException("Device code expired before authorization completed; run `crsctl login` again.")
            }
            val form = formBody(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to clientId,
                "device_code" to deviceCode,
            )
            val response = exchange.send(postForm(tokenUri, form))
            val status = response.statusCode()
            if (status in 200..299) {
                return decode(response.body(), TokenResponse.serializer(), "token response")
            }
            val error = parseError(response.body())
            when (error?.error) {
                "authorization_pending" -> sleeper.sleep(currentInterval * 1000L)
                "slow_down" -> {
                    currentInterval += 5
                    sleeper.sleep(currentInterval * 1000L)
                }
                "expired_token" -> throw DeviceFlowException(
                    "Device code expired before authorization completed; run `crsctl login` again.",
                )
                "access_denied" -> throw DeviceFlowException(
                    "Authorization was denied. Run `crsctl login` to try again.",
                )
                else -> throw DeviceFlowException(
                    "Token polling failed: HTTP $status ${error?.error ?: errorDetail(response.body())}",
                )
            }
        }
    }

    /**
     * Exchanges a refresh token for a fresh [TokenResponse]. Keycloak may rotate the refresh token,
     * so the caller MUST persist [TokenResponse.refreshToken] when it is present.
     */
    fun refresh(
        issuer: String,
        clientId: String,
        refreshToken: String,
    ): TokenResponse {
        val form = formBody(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
        )
        val request = postForm("${issuerBase(issuer)}/protocol/openid-connect/token", form)
        val response = exchange.send(request)
        if (response.statusCode() !in 200..299) {
            val error = parseError(response.body())
            throw DeviceFlowException(
                "Token refresh failed: HTTP ${response.statusCode()} ${error?.error ?: errorDetail(response.body())}",
            )
        }
        return decode(response.body(), TokenResponse.serializer(), "token response")
    }

    /**
     * Best-effort token revocation (RFC 7009). Returns true when the server accepted the revocation
     * (2xx); returns false on a non-2xx so the caller can warn-but-continue rather than fail logout.
     */
    fun revoke(
        issuer: String,
        clientId: String,
        refreshToken: String,
    ): Boolean {
        val form = formBody(
            "client_id" to clientId,
            "token" to refreshToken,
            "token_type_hint" to "refresh_token",
        )
        val request = postForm("${issuerBase(issuer)}/protocol/openid-connect/revoke", form)
        return exchange.send(request).statusCode() in 200..299
    }

    private fun issuerBase(issuer: String): String = issuer.trimEnd('/')

    private fun postForm(
        uri: String,
        body: String,
    ): HttpRequest =
        HttpRequest
            .newBuilder()
            .uri(URI.create(uri))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun formBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun parseError(body: String?): TokenErrorResponse? {
        if (body.isNullOrBlank()) {
            return null
        }
        return try {
            Json.decodeFromString(TokenErrorResponse.serializer(), body)
        } catch (e: SerializationException) {
            null
        }
    }

    private fun errorDetail(body: String?): String {
        val parsed = parseError(body)
        return parsed?.errorDescription ?: parsed?.error ?: (body?.take(200).orEmpty())
    }

    private fun <T> decode(
        body: String?,
        serializer: kotlinx.serialization.KSerializer<T>,
        what: String,
    ): T {
        try {
            return Json.decodeFromString(serializer, body ?: "")
        } catch (e: SerializationException) {
            throw DeviceFlowException("Failed to parse $what: ${e.message}", e)
        }
    }

    companion object {
        /** Builds the scope string, appending `offline_access` when a refresh token is wanted. */
        fun scopeFor(offline: Boolean): String = if (offline) "openid offline_access" else "openid"
    }
}
