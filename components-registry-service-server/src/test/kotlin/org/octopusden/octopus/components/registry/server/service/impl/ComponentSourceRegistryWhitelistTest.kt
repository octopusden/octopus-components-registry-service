package org.octopusden.octopus.components.registry.server.service.impl

import java.util.Optional
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository

/**
 * PR #192 review fixup 3.5: `setComponentSource` must case-normalize and
 * whitelist the `source` argument so a routing blackhole cannot be created
 * by writing an unexpected casing.
 *
 * The Opus adversarial review (P1-C on Group 1 + plan v3 Sonnet review)
 * flagged that the previous implementation persisted any string verbatim.
 * `ComponentRoutingResolver.resolverFor` does an exact `getSource(name) ==
 * "db"` check, and `getDbComponentNames()` calls `findBySource("db")`
 * (case-sensitive query). Storing `"DB"`, `"DB "`, or any whitespace-
 * decorated variant routes the affected component to gitResolver and
 * excludes it from the db-routing set → 404 for any DB-only data.
 *
 * Pure Mockito test: no Spring context.
 */
@Timeout(10, unit = TimeUnit.SECONDS)
class ComponentSourceRegistryWhitelistTest {
    private lateinit var repository: ComponentSourceRepository
    private lateinit var properties: ComponentsRegistryProperties
    private lateinit var registry: ComponentSourceRegistryImpl

    @BeforeEach
    fun setUp() {
        repository = mock(ComponentSourceRepository::class.java)
        properties = mock(ComponentsRegistryProperties::class.java)
        doReturn(Optional.empty<ComponentSourceEntity>()).`when`(repository).findById(any())
        registry = ComponentSourceRegistryImpl(repository, properties)
    }

    @Test
    @DisplayName("setComponentSource — uppercase 'DB' is case-normalized to lowercase 'db'")
    fun setComponentSource_uppercaseDb_normalizedToDb() {
        registry.setComponentSource("widget", "DB")

        val captor = ArgumentCaptor.forClass(ComponentSourceEntity::class.java)
        verify(repository).save(captor.capture())
        assertEquals("db", captor.value.source)
    }

    @Test
    @DisplayName("setComponentSource — uppercase 'GIT' is case-normalized to lowercase 'git'")
    fun setComponentSource_uppercaseGit_normalizedToGit() {
        registry.setComponentSource("widget", "GIT")

        val captor = ArgumentCaptor.forClass(ComponentSourceEntity::class.java)
        verify(repository).save(captor.capture())
        assertEquals("git", captor.value.source)
    }

    @Test
    @DisplayName("setComponentSource — trailing whitespace is trimmed and normalized ('  Db ' → 'db')")
    fun setComponentSource_whitespaceWrapped_trimmedAndNormalized() {
        registry.setComponentSource("widget", "  Db ")

        val captor = ArgumentCaptor.forClass(ComponentSourceEntity::class.java)
        verify(repository).save(captor.capture())
        assertEquals("db", captor.value.source)
    }

    @Test
    @DisplayName("setComponentSource — already-canonical 'db' passes through unchanged")
    fun setComponentSource_canonicalDb_unchanged() {
        registry.setComponentSource("widget", "db")

        val captor = ArgumentCaptor.forClass(ComponentSourceEntity::class.java)
        verify(repository).save(captor.capture())
        assertEquals("db", captor.value.source)
    }

    @Test
    @DisplayName("setComponentSource — non-whitelist value 'vcs' rejected with IllegalArgumentException")
    fun setComponentSource_nonWhitelistVcs_rejected() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                registry.setComponentSource("widget", "vcs")
            }
        assertEquals(true, ex.message?.contains("vcs"), "Error message must echo the rejected source: '${ex.message}'")
    }

    @Test
    @DisplayName("setComponentSource — empty string rejected")
    fun setComponentSource_emptyString_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            registry.setComponentSource("widget", "")
        }
    }

    @Test
    @DisplayName("setComponentSource — random uppercase 'DATABASE' rejected (not in whitelist after lowercase)")
    fun setComponentSource_uppercaseDatabase_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            registry.setComponentSource("widget", "DATABASE")
        }
    }

    @Test
    @DisplayName(
        "setComponentSource — error message truncates an oversized rejected source (PR #247 P2-A guard)",
    )
    fun setComponentSource_oversizedRejected_messageTruncated() {
        val huge = "x".repeat(500) + "INJECT"
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                registry.setComponentSource("widget", huge)
            }
        val msg = ex.message ?: ""
        assertEquals(
            false,
            msg.contains("INJECT"),
            "Error message must NOT echo the full payload; got first 200 chars: '${msg.take(200)}'",
        )
        assertEquals(
            true,
            msg.contains("…"),
            "Error message must include ellipsis marker for truncated payloads; got: '${msg.take(200)}'",
        )
    }

    @Test
    @DisplayName(
        "setComponentSource — short newline-injected source is sanitized in error (no raw \\n, escaped as \\x0A)",
    )
    fun setComponentSource_newlineInjected_sanitized() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                registry.setComponentSource("widget", "bad\nWARN forged")
            }
        val msg = ex.message ?: ""
        assertEquals(
            false,
            msg.contains("\n"),
            "Error message must NOT contain a raw newline (log-injection guard); got: '${msg.take(200)}'",
        )
        assertEquals(
            true,
            msg.contains("\\x0A"),
            "Error message must escape the newline as '\\\\x0A'; got: '${msg.take(200)}'",
        )
    }

    @Test
    @DisplayName(
        "setComponentSource — CR / TAB / DEL / C1-control chars all escaped, never echoed raw",
    )
    fun setComponentSource_assortedControls_sanitized() {
        // CR (0x0D), TAB (0x09), DEL (0x7F), C1 control NEL (0x85) — none should land verbatim.
        val payload = "v" + 0x0D.toChar() + "A" + 0x09.toChar() + "B" + 0x7F.toChar() + "C" + 0x85.toChar() + "D"
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                registry.setComponentSource("widget", payload)
            }
        val msg = ex.message ?: ""
        assertEquals(false, msg.contains(0x0D.toChar()), "no raw CR; msg='${msg.take(200)}'")
        assertEquals(false, msg.contains(0x09.toChar()), "no raw TAB; msg='${msg.take(200)}'")
        assertEquals(false, msg.contains(0x7F.toChar()), "no raw DEL; msg='${msg.take(200)}'")
        assertEquals(false, msg.contains(0x85.toChar()), "no raw NEL; msg='${msg.take(200)}'")
        assertEquals(true, msg.contains("\\x0D"), "CR escaped; msg='${msg.take(200)}'")
        assertEquals(true, msg.contains("\\x09"), "TAB escaped; msg='${msg.take(200)}'")
        assertEquals(true, msg.contains("\\x7F"), "DEL escaped; msg='${msg.take(200)}'")
        assertEquals(true, msg.contains("\\x85"), "NEL escaped; msg='${msg.take(200)}'")
    }
}
