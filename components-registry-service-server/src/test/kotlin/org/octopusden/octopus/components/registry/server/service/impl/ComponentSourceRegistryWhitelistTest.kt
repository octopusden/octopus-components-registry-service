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

    @Test
    @DisplayName(
        "setComponentSource — truncation at cap boundary never splits a \\xNN escape mid-sequence " +
            "(final-review Opus P1-2 boundary guard)",
    )
    fun setComponentSource_truncationAtBoundary_neverSplitsEscape() {
        // 100 SOH (0x01) controls followed by an INJECT marker. With the previous
        // sanitize-then-truncate order, sanitized output was 100×4+6 = 406 chars and
        // take(80) cut INSIDE the 20th `\x01`. After truncate-then-sanitize, take is
        // applied to the raw 100×SOH+INJECT (=106 chars) capped at 80 (still 80×SOH),
        // then sanitized → exactly 80 `\x01` escapes, no INJECT, no dangling escape.
        val payload = 0x01.toChar().toString().repeat(100) + "INJECT"
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                registry.setComponentSource("widget", payload)
            }
        val msg = ex.message ?: ""
        // No INJECT (truncated out of scope).
        assertEquals(false, msg.contains("INJECT"), "INJECT must not survive truncation; msg='${msg.take(200)}'")
        // Ellipsis marker for truncated payload.
        assertEquals(true, msg.contains("…"), "ellipsis must mark truncation; msg='${msg.take(200)}'")
        // Guard against dangling `\x` or `\x0` — every `\x` must be followed by exactly
        // 2 hex digits. Walk forward through the message collecting actual `\x` positions.
        val backslashXIndices = mutableListOf<Int>()
        var searchFrom = 0
        while (true) {
            val idx = msg.indexOf("\\x", searchFrom)
            if (idx < 0) break
            backslashXIndices += idx
            searchFrom = idx + 2
        }
        // At least one `\x01` escape must be present (sanity check the payload reached the message).
        assertEquals(true, backslashXIndices.isNotEmpty(), "no \\x escape in message; msg='${msg.take(200)}'")
        for (idx in backslashXIndices) {
            val tail = msg.substring(idx)
            assertEquals(
                true,
                tail.length >= 4 && tail[2].isHexDigit() && tail[3].isHexDigit(),
                "dangling \\x escape at idx $idx; tail='${tail.take(10)}'",
            )
        }
    }

    @Test
    @DisplayName(
        "setComponentSource — UTF-16 surrogate pair at the cap boundary is dropped cleanly " +
            "(no lone high surrogate in echoed message)",
    )
    fun setComponentSource_surrogateAtBoundary_droppedCleanly() {
        // Build a payload where a surrogate pair lands exactly at position 79..80.
        // Astral-plane code point U+1F600 (😀) is one such pair (high=0xD83D, low=0xDE00).
        // 79 printable ASCII chars + the emoji (2 UTF-16 code units) = position 79 high, 80 low.
        val payload = "a".repeat(79) + "😀" + "TAIL"
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                registry.setComponentSource("widget", payload)
            }
        val msg = ex.message ?: ""
        // No lone high surrogate in the message.
        for (i in msg.indices) {
            val c = msg[i]
            if (c.isHighSurrogate()) {
                val next = msg.getOrNull(i + 1)
                assertEquals(
                    true,
                    next != null && next.isLowSurrogate(),
                    "lone high surrogate at index $i; msg='${msg.take(200)}'",
                )
            }
        }
        // TAIL was past the cap, so it must not survive.
        assertEquals(false, msg.contains("TAIL"), "TAIL must not survive truncation; msg='${msg.take(200)}'")
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'
}
