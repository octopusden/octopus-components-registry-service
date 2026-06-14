package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.testing.test
import org.octopusden.octopus.components.registry.cli.auth.CommandResult
import org.octopusden.octopus.components.registry.cli.auth.CommandRunner
import org.octopusden.octopus.components.registry.cli.config.CrsctlConfig
import org.octopusden.octopus.components.registry.cli.crsctl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `crsctl logout` must ALWAYS clear the locally stored refresh token, even when the OIDC config
 * needed for server-side revocation is missing/broken (otherwise deleting the config would strand a
 * token on disk forever). Driven through the full command tree with a fake `security` runner and an
 * EMPTY config (so OIDC resolution fails).
 */
class LogoutTest {

    /** Fakes the `security` CLI: load returns a stored token; records whether delete was invoked. */
    private class KeychainRunner : CommandRunner {
        var deleteCalled = false
        override fun run(args: List<String>, stdin: String?): CommandResult = when {
            args.contains("find-generic-password") -> CommandResult(0, "stored-refresh-token\n", "")
            args.contains("delete-generic-password") -> {
                deleteCalled = true
                CommandResult(0, "", "")
            }
            else -> CommandResult(0, "", "")
        }
    }

    @Test
    fun `logout clears the local token even when OIDC config is missing`() {
        val runner = KeychainRunner()
        // EMPTY config + no --env -> resolveOidcConfig() throws; the token must still be cleared.
        val result = crsctl(configLoader = { CrsctlConfig.EMPTY }, commandRunner = runner).test(listOf("logout"))

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(runner.deleteCalled, "the stored refresh token must be cleared even without OIDC config")
        assertTrue(
            result.stderr.contains("clearing local credentials anyway"),
            "a best-effort warning is expected on stderr: ${result.stderr}",
        )
    }

    @Test
    fun `logout with no stored token is a clean no-op`() {
        val runner = object : CommandRunner {
            override fun run(args: List<String>, stdin: String?): CommandResult =
                // exit 44 = item-not-found -> store.load() returns null.
                CommandResult(44, "", "not found")
        }
        val result = crsctl(configLoader = { CrsctlConfig.EMPTY }, commandRunner = runner).test(listOf("logout"))

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("Already logged out"), result.stdout)
    }
}
