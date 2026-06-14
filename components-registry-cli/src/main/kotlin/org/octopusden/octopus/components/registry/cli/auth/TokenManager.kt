package org.octopusden.octopus.components.registry.cli.auth

/**
 * Thrown when an operation needs an authenticated identity but no usable credential is available
 * (no stored refresh token). The command layer maps this to
 * [org.octopusden.octopus.components.registry.cli.client.ExitCode.AUTH_REQUIRED].
 */
class AuthRequiredException(
    message: String = "Not logged in. Run `crsctl login` first.",
) : RuntimeException(message)

/**
 * Owns the short-lived access token for a single crsctl process.
 *
 * Given an issuer, a public client id, and a [CredentialStore] holding the long-lived refresh token,
 * [accessToken] returns a currently-valid access token, transparently refreshing (with rotation)
 * when the cached token is missing or within [refreshSkewSeconds] of expiry. A rotated refresh token
 * (when the provider returns a new one) is persisted back to the store.
 *
 * If the store holds no refresh token, [accessToken] throws [AuthRequiredException] rather than
 * attempting an interactive login — login is an explicit user action (`crsctl login`).
 */
class TokenManager(
    private val issuer: String,
    private val clientId: String,
    private val store: CredentialStore,
    private val deviceFlow: DeviceFlowClient = DeviceFlowClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val refreshSkewSeconds: Long = 30,
) {
    private var cachedAccessToken: String? = null
    private var expiresAtMillis: Long = 0

    /** Returns a valid access token, refreshing-with-rotation when needed. */
    fun accessToken(): String {
        val cached = cachedAccessToken
        if (cached != null && nowMillis() < expiresAtMillis - refreshSkewSeconds * 1000L) {
            return cached
        }
        val refreshToken = store.load()
            ?: throw AuthRequiredException()
        val response = deviceFlow.refresh(issuer, clientId, refreshToken)
        // Rotation: persist the refresh token whenever the provider returned one (save is idempotent).
        // Never save/wipe when the response omits a refresh token.
        response.refreshToken?.let { store.save(it) }
        cachedAccessToken = response.accessToken
        expiresAtMillis = nowMillis() + response.expiresIn * 1000L
        return response.accessToken
    }
}
