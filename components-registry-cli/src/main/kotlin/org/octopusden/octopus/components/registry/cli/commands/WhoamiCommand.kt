package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import org.octopusden.octopus.components.registry.cli.CliContext
import org.octopusden.octopus.components.registry.cli.auth.AuthRequiredException
import org.octopusden.octopus.components.registry.cli.client.ConfigResolutionException
import org.octopusden.octopus.components.registry.cli.config.EffectiveTarget
import org.octopusden.octopus.components.registry.cli.model.User
import org.octopusden.octopus.components.registry.cli.output.Renderer

/**
 * `crsctl whoami` — report the current identity.
 *
 * Credential resolution order:
 *   1. An explicit token (`--token` / `CRS_TOKEN`) — used verbatim.
 *   2. A stored refresh token — exchanged for an access token via the
 *      [org.octopusden.octopus.components.registry.cli.auth.TokenManager].
 *   3. Neither — print the static anonymous line and exit 0, making NO HTTP call (`/auth/me` is
 *      authenticated-only and anonymous callers implicitly have ACCESS_COMPONENTS on current CRS).
 *
 * With a resolvable token it calls GET /auth/me and renders the [User] (username + roles) honouring
 * `-o json|table`.
 */
class WhoamiCommand : CliktCommand(
    name = "whoami",
    help = "Show the current identity. Anonymous (no credential) reports a static line without calling the server.",
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runCommand {
        val target = ctx.resolveTarget()
        val token = resolveToken(target)
        if (token == null) {
            emit("anonymous; for current CRS versions ACCESS_COMPONENTS is implied")
            return@runCommand
        }

        val client = ctx.clientFor(target.copy(token = token))
        val user = client.getJson("/auth/me", User.serializer())
        render(
            ctx,
            json = { Renderer.renderJson(user, User.serializer()) },
            table = { userTable(user) },
        )
    }

    /**
     * Returns a usable bearer token, or null when the caller is anonymous.
     *
     * An explicit token wins. Otherwise, if a refresh token is stored AND the active profile carries
     * OIDC settings, the refresh token is exchanged for an access token. With no stored credential
     * ([AuthRequiredException]) or no profile-level OIDC config ([ConfigResolutionException]) the
     * caller is treated as anonymous rather than failing — `whoami` must work on a bare `--crs-url`.
     */
    private fun resolveToken(target: EffectiveTarget): String? {
        if (!target.token.isNullOrBlank()) {
            return target.token
        }
        return try {
            ctx.tokenManager().accessToken()
        } catch (e: AuthRequiredException) {
            null
        } catch (e: ConfigResolutionException) {
            null
        }
    }
}

private fun userTable(user: User): String {
    val roleNames = user.roles.joinToString(", ") { it.name }
    val authorities = user.roles.flatMap { it.permissions }.distinct().joinToString(", ")
    return Renderer.renderTable(
        headers = listOf("FIELD", "VALUE"),
        rows = listOf(
            listOf("username", user.username),
            listOf("groups", user.groups.joinToString(", ")),
            listOf("roles", roleNames),
            listOf("authorities", authorities),
        ),
    )
}
