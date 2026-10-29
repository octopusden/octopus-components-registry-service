package org.octopusden.octopus.components.registry.cli.auth

import org.octopusden.octopus.components.registry.cli.config.ConfigLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Persists the long-lived OIDC refresh token between crsctl invocations.
 *
 * Only the refresh token is stored; the short-lived access token lives in memory inside the
 * [TokenManager] for the duration of a single process and is never written to disk.
 */
interface CredentialStore {
    /** Returns the stored refresh token, or null when none has been saved. */
    fun load(): String?

    /** Persists (creating or replacing) the refresh token. */
    fun save(refreshToken: String)

    /** Removes any stored refresh token. A no-op when nothing is stored. */
    fun clear()
}

/**
 * Thrown when the secure (Keychain) credential path fails. Per the security policy a Keychain
 * failure during save/load is a HARD error: we never silently fall back to a plaintext file, because
 * doing so would downgrade the user's security posture without their knowledge.
 */
class CredentialStoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * macOS Keychain-backed [CredentialStore] implemented via the system `security` CLI:
 *   - save  -> `security add-generic-password -U -w <secret>` (-U updates an existing item in place).
 *             The secret is passed as the `-w` argument: a BARE `-w` (no value) makes `security`
 *             prompt interactively on the tty (with a retype confirmation) and does NOT read from a
 *             piped stdin, so the only reliable non-interactive path is the `-w <value>` argument.
 *             Trade-off: the secret is briefly visible in `ps` for the lifetime of the subprocess.
 *             TODO(spike): replace the `security` CLI with a native Keychain API (Security.framework
 *             via a small helper / JNA) to store the secret without any `ps` exposure.
 *   - load  -> `security find-generic-password -w` (-w prints just the secret to STDOUT)
 *   - clear -> `security delete-generic-password`
 *
 * The `security` process is launched through an injectable [CommandRunner] so tests can fake it
 * without touching the real Keychain.
 *
 * Failure policy:
 *   - save: a non-zero exit is a hard error -> [CredentialStoreException] (NO silent swallow).
 *   - load: "item not found" (exit 44) maps to null (a legitimate "not logged in" state); any other
 *     non-zero exit is a hard error.
 *   - clear: "item not found" is tolerated (idempotent delete); any other non-zero exit throws.
 */
class KeychainCredentialStore(
    private val runner: CommandRunner,
    private val service: String = DEFAULT_SERVICE,
    private val account: String = DEFAULT_ACCOUNT,
) : CredentialStore {
    override fun load(): String? {
        val result = runner.run(
            listOf("security", "find-generic-password", "-s", service, "-a", account, "-w"),
            null,
        )
        if (result.exitCode == 0) {
            // -w prints the secret followed by a trailing newline.
            return result.stdout.trimEnd('\n', '\r')
        }
        if (result.exitCode == ITEM_NOT_FOUND) {
            return null
        }
        throw CredentialStoreException(
            "Keychain lookup failed (exit ${result.exitCode}): ${result.stderr.trim()}",
        )
    }

    override fun save(refreshToken: String) {
        // `-w <value>` is the only reliable NON-INTERACTIVE write: a bare `-w` makes `security`
        // prompt + retype on the tty (it does not read a piped stdin), which silently fails to store
        // anything. The secret is therefore briefly `ps`-visible for the subprocess lifetime.
        // TODO(spike): replace with a native Keychain API to remove that exposure.
        val result = runner.run(
            listOf(
                "security",
                "add-generic-password",
                "-U",
                "-s",
                service,
                "-a",
                account,
                "-w",
                refreshToken,
            ),
            null,
        )
        if (result.exitCode != 0) {
            throw CredentialStoreException(
                "Keychain save failed (exit ${result.exitCode}): ${result.stderr.trim()}",
            )
        }
    }

    override fun clear() {
        val result = runner.run(
            listOf("security", "delete-generic-password", "-s", service, "-a", account),
            null,
        )
        if (result.exitCode == 0 || result.exitCode == ITEM_NOT_FOUND) {
            return
        }
        throw CredentialStoreException(
            "Keychain delete failed (exit ${result.exitCode}): ${result.stderr.trim()}",
        )
    }

    companion object {
        const val DEFAULT_SERVICE = "crsctl"
        const val DEFAULT_ACCOUNT = "refresh-token"

        /** `security`'s documented "the specified item could not be found in the keychain" exit code. */
        const val ITEM_NOT_FOUND = 44
    }
}

/**
 * Plaintext-file [CredentialStore] at `<configDir>/credentials`, file mode 0600.
 *
 * This is INSECURE and only selected when the user explicitly passes `--insecure-token-store`.
 * Constructing it emits a one-time warning to STDERR; the file permissions are tightened to
 * owner-read/write only on every save.
 */
class InsecureFileCredentialStore(
    private val file: Path = ConfigLoader.configDir().resolve(CREDENTIALS_FILE_NAME),
    private val warn: (String) -> Unit = { System.err.println(it) },
) : CredentialStore {
    init {
        warn(
            "warning: storing the refresh token in plaintext at $file (--insecure-token-store). " +
                "Prefer the system keychain; this file is readable by your user account only (0600).",
        )
    }

    override fun load(): String? {
        if (!Files.exists(file)) {
            return null
        }
        val text = Files.readString(file).trim()
        return text.ifBlank { null }
    }

    override fun save(refreshToken: String) {
        val dir = file.parent
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir)
        }
        if (!Files.exists(file)) {
            // Create the file owner-only (0600) ATOMICALLY before any secret is written, closing the
            // TOCTOU window where a world-readable file could briefly hold the token.
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        }
        Files.writeString(file, refreshToken, StandardOpenOption.TRUNCATE_EXISTING)
        // Re-assert perms after write (covers the overwrite path where the file pre-existed).
        setOwnerOnlyPermissions(file)
    }

    override fun clear() {
        Files.deleteIfExists(file)
    }

    private fun setOwnerOnlyPermissions(target: Path) {
        val view = Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView::class.java)
        if (view != null) {
            val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            Files.setPosixFilePermissions(target, perms)
        }
    }

    companion object {
        const val CREDENTIALS_FILE_NAME = "credentials"

        /** Convenience for callers that want the canonical 0600 permission set. */
        val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    }
}

/**
 * Selects the credential store. When [insecure] is true the plaintext file store is used (and warns);
 * otherwise the macOS Keychain store is used, driven by [runner].
 */
fun credentialStore(
    insecure: Boolean,
    runner: CommandRunner,
): CredentialStore =
    if (insecure) {
        InsecureFileCredentialStore()
    } else {
        KeychainCredentialStore(runner)
    }
