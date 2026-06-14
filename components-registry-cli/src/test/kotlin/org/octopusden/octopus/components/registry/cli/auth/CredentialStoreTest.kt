package org.octopusden.octopus.components.registry.cli.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused coverage of [KeychainCredentialStore]'s `security` CLI wiring: the hard-fail-on-nonzero
 * policy, the "item not found" (exit 44) special-casing, and that the secret is written via the
 * `-w <value>` argument (the only reliable non-interactive path; a bare `-w` would prompt on the tty).
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
    fun `save writes the secret as the -w argument non-interactively`() {
        val secret = "super-secret-refresh-token"
        val runner = RecordingRunner(listOf(ok()))
        KeychainCredentialStore(runner).save(secret)

        val argv = runner.argvs.single()
        // `-w <value>` is required: a bare `-w` would prompt+retype on the tty and store nothing.
        val wIndex = argv.indexOf("-w")
        assertTrue(wIndex >= 0, "a -w flag is expected: $argv")
        assertEquals(secret, argv.getOrNull(wIndex + 1), "the secret must be the value after -w")
        assertTrue(argv.contains("-U"), "must update-in-place with -U")
        assertNull(runner.stdins.single(), "no interactive stdin is used")
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
