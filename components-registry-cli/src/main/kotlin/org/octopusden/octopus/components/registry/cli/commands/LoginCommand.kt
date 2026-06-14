package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.octopusden.octopus.components.registry.cli.CliContext
import org.octopusden.octopus.components.registry.cli.auth.DeviceFlowClient

/**
 * `crsctl login [--offline]` — authenticate via the OIDC Device Authorization Grant (RFC 8628).
 *
 * Resolves the keycloak issuer + public client id from the active profile (clear error if absent),
 * starts the device flow, prints the verification URI + user code to STDOUT (it deliberately does
 * NOT shell out to mac `open`), polls until the user completes authorization, and persists the
 * resulting refresh token via the selected [org.octopusden.octopus.components.registry.cli.auth.CredentialStore].
 *
 * `--offline` requests the `offline_access` scope so the provider returns a refresh token (needed for
 * non-interactive token refresh between invocations).
 */
class LoginCommand : CliktCommand(
    name = "login",
    help = "Log in via the OIDC device authorization grant and store the refresh token.",
) {
    private val ctx by requireObject<CliContext>()
    private val offline by option(
        "--offline",
        help = "Request the offline_access scope so a long-lived refresh token is issued.",
    ).flag()

    override fun run() = runCommand {
        val oidc = ctx.resolveOidcConfig()
        val deviceFlow = ctx.deviceFlowClient
        val scope = DeviceFlowClient.scopeFor(offline)

        val authorization = deviceFlow.requestDeviceAuthorization(oidc.issuer, oidc.clientId, scope)

        emit("To sign in, open ${authorization.verificationUri} and enter code ${authorization.userCode}")
        authorization.verificationUriComplete?.let { complete ->
            emit("Or open this URL directly (code prefilled): $complete")
        }
        emit("Waiting for authorization...")

        val token = deviceFlow.pollToken(
            issuer = oidc.issuer,
            clientId = oidc.clientId,
            deviceCode = authorization.deviceCode,
            interval = authorization.interval,
            expiresInSeconds = authorization.expiresIn,
        )

        val refreshToken = token.refreshToken
            ?: error(
                "Login succeeded but the provider returned no refresh token; pass --offline to " +
                    "request the offline_access scope.",
            )
        ctx.credentialStore().save(refreshToken)
        emit("Login successful. Credentials stored.")
    }
}
