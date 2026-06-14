package org.octopusden.octopus.components.registry.cli.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused coverage of [KeychainCredentialStore]'s `security` CLI wiring: the hard-fail-on-nonzero
 * policy, the "item not found" (exit 44) special-casing, and — critically — that the secret travels
 * via STDIN and never on the argv (no `ps` exposure).
 */
class CredentialStoreTest {

    /** Records both argv and stdin per call so the secret's transport channel can be asserted. */
    private class RecordingRunner(
        private val results: List<CommandResult>,
    ) : CommandRunner {
        val argvs = mutableListOf<List<String>>()
        val stdins = mutableListOf<String?>()
        override fun run(args: List<String>, stdin: String?): CommandResult {
            argvs += args
            stdins += stdin
            return results[argvs.size - 1]
        }
    }

    private fun ok() = CommandResult(0, "", "")
    private fun notFound() = CommandResult(KeychainCredentialStore.ITEM_NOT_FOUND, "", "not found")
    private fun fail() = CommandResult(1, "", "boom")

    @Test
    fun `save passes the secret via stdin and never on the argv`() {
        val secret = "super-secret-refresh-token"
        val runner = RecordingRunner(listOf(ok()))
        KeychainCredentialStore(runner).save(secret)

        val argv = runner.argvs.single()
        assertFalse(argv.contains(secret), "refresh token must NOT appear on the argv: $argv")
        assertTrue(argv.contains("-w"), "a bare -w flag is expected so the secret is read from stdin")
        assertEquals(secret, runner.stdins.single(), "the refresh token must be fed via stdin")
    }

    @Test
    fun `save hard-fails on a non-zero exit`() {
        val runner = RecordingRunner(listOf(fail()))
        assertFailsWith<CredentialStoreException> { KeychainCredentialStore(runner).save("x") }
    }

    @Test
    fun `load returns the trimmed secret on exit 0`() {
        val runner = RecordingRunner(listOf(CommandResult(0, "the-token\n", "")))
        assertEquals("the-token", KeychainCredentialStore(runner).load())
    }

    @Test
    fun `load returns null on exit 44`() {
        val runner = RecordingRunner(listOf(notFound()))
        assertNull(KeychainCredentialStore(runner).load())
    }

    @Test
    fun `load hard-fails on any other non-zero exit`() {
        val runner = RecordingRunner(listOf(fail()))
        assertFailsWith<CredentialStoreException> { KeychainCredentialStore(runner).load() }
    }

    @Test
    fun `clear is idempotent on exit 0 and exit 44`() {
        KeychainCredentialStore(RecordingRunner(listOf(ok()))).clear()
        KeychainCredentialStore(RecordingRunner(listOf(notFound()))).clear()
    }

    @Test
    fun `clear hard-fails on any other non-zero exit`() {
        val runner = RecordingRunner(listOf(fail()))
        assertFailsWith<CredentialStoreException> { KeychainCredentialStore(runner).clear() }
    }
}
