package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import org.octopusden.octopus.components.registry.cli.CliContext

/**
 * `crsctl whoami` — report the current identity.
 *
 * Without a resolved token there is nothing to ask the server: `/auth/me` is authenticated-only, and
 * for current CRS versions anonymous callers implicitly have ACCESS_COMPONENTS. So the no-token branch
 * prints a static line and exits 0 — it deliberately makes NO HTTP call.
 */
class WhoamiCommand : CliktCommand(
    name = "whoami",
    help = "Show the current identity. Anonymous (no token) reports a static line without calling the server.",
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runCommand {
        val target = ctx.resolveTarget()
        if (target.token.isNullOrBlank()) {
            emit("anonymous; for current CRS versions ACCESS_COMPONENTS is implied")
            return@runCommand
        }
        // TODO(auth-layer): with a resolved token, call GET /auth/me and render the User
        //  (username / groups / roles). Added in the auth layer; until then we cannot describe a
        //  token-bearing identity, so fall back to the anonymous-style note.
        emit("authenticated (token present); /auth/me lookup is implemented in the auth layer")
    }
}
