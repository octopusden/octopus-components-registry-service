package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import org.octopusden.octopus.components.registry.cli.CliContext

/**
 * `crsctl logout` — revoke-then-clear.
 *
 * If a refresh token is stored it is first revoked at the provider (RFC 7009) and then removed from
 * the credential store. Revocation is best-effort: if it fails, the local token is STILL cleared and
 * the command exits 0 with a warning (a stale server-side session is preferable to a token lingering
 * on disk). When no token is stored the command is a no-op success.
 */
class LogoutCommand :
    CliktCommand(
        name = "logout",
        help = "Revoke and remove the stored refresh token.",
    ) {
    private val ctx by requireObject<CliContext>()

    override fun run() =
        runCommand {
            val store = ctx.credentialStore()
            val refreshToken = store.load()
            if (refreshToken == null) {
                emit("Already logged out.")
                return@runCommand
            }

            // Revoke FIRST (best-effort), then ALWAYS clear. Any failure is recoverable — including a
            // missing/broken OIDC profile that prevents us from even attempting revocation: we warn and
            // still clear the local token (exit 0), so a stale server session never strands a token on disk.
            try {
                val oidc = ctx.resolveOidcConfig()
                val revoked = ctx.deviceFlowClient.revoke(oidc.issuer, oidc.clientId, refreshToken)
                if (!revoked) {
                    echo("warning: provider did not confirm revocation; clearing local credentials anyway.", err = true)
                }
            } catch (e: Exception) {
                echo("warning: could not revoke the token (${e.message}); clearing local credentials anyway.", err = true)
            }

            store.clear()
            emit("Logged out.")
        }
}
