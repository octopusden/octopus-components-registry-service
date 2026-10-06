package org.octopusden.octopus.components.registry.server.service.impl

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.repository.AuditChangeStats
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentChangeStampRow
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.RenderedComponentCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** SYS-100: as-code text search — matching, result shaping and index freshness. */
class ComponentCodeSearchServiceTest {
    private val componentManagementService = mock<ComponentManagementService>()
    private val componentRepository = mock<ComponentRepository>()
    private val auditLogRepository = mock<AuditLogRepository>()
    private val clock = MutableClock(Instant.parse("2026-10-05T10:00:00Z"))
    private val service = ComponentCodeSearchService(componentManagementService, componentRepository, auditLogRepository, clock)

    private val alpha =
        RenderedComponentCode(
            "alpha",
            """
            alpha {
                componentOwner = "jdoe"
                vcsSettings {
                    vcsUrl = "ssh://git@github.com/org/alpha.git"
                }
                "[1.5,)" {
                    jira {
                        projectKey = "ALPHA"
                    }
                }
            }
            """.trimIndent() + "\n",
            id = ALPHA_ID,
        )
    private val beta =
        RenderedComponentCode(
            "beta",
            """
            beta {
                componentOwner = "JDoe"
                groupId = "org.example.beta"
            }
            """.trimIndent() + "\n",
            archived = true,
            id = UUID.randomUUID(),
        )

    @BeforeEach
    fun setUp() {
        stamp(count = 2, versionSum = 7, auditMaxId = 100)
        whenever(componentManagementService.renderAllComponentsAsCode()).thenReturn(listOf(alpha, beta))
    }

    @Test
    @DisplayName("SYS-100: case-insensitive substring, grouped by component with line numbers and block path")
    fun `SYS-100 substring match is case-insensitive with line numbers and block path`() {
        val result = service.search("jdoe")

        assertThat(result.totalComponents).isEqualTo(2)
        assertThat(result.truncated).isFalse()
        assertThat(result.results.map { it.componentKey }).containsExactly("alpha", "beta")
        assertThat(result.results.first().id).isEqualTo(ALPHA_ID)
        val line =
            result.results
                .first()
                .matches
                .single()
        assertThat(line.line).isEqualTo(2)
        assertThat(line.text).isEqualTo("componentOwner = \"jdoe\"")
        assertThat(line.path).containsExactly("alpha")
    }

    @Test
    @DisplayName("SYS-100: a hit inside a version-range block reports the enclosing range in its path")
    fun `SYS-100 hit inside a version-range block reports the range in its path`() {
        val line =
            service
                .search("ALPHA\"")
                .results
                .single()
                .matches
                .single()

        assertThat(line.line).isEqualTo(8)
        assertThat(line.path).containsExactly("alpha", "\"[1.5,)\"", "jira")
    }

    @Test
    @DisplayName("SYS-100: archived narrows to archived / active components; omitted searches both")
    fun `SYS-100 archived narrows to archived or active components`() {
        assertThat(service.search("jdoe", archived = true).results.map { it.componentKey }).containsExactly("beta")
        assertThat(service.search("jdoe", archived = false).results.map { it.componentKey }).containsExactly("alpha")
    }

    @Test
    @DisplayName("SYS-100: limit cuts the component list and flags truncation; totalComponents counts all")
    fun `SYS-100 limit cuts the component list and flags truncation`() {
        val result = service.search("componentOwner", limit = 1)

        assertThat(result.results).hasSize(1)
        assertThat(result.totalComponents).isEqualTo(2)
        assertThat(result.truncated).isTrue()
    }

    @Test
    @DisplayName("SYS-100: maxMatchesPerComponent caps lines; matchCount still reports every match")
    fun `SYS-100 maxMatchesPerComponent caps lines while matchCount counts all`() {
        // `= "` hits alpha's componentOwner, vcsUrl and projectKey lines.
        val hit =
            service
                .search("= \"", archived = false, maxMatchesPerComponent = 2)
                .results
                .single()

        assertThat(hit.matches).hasSize(2)
        assertThat(hit.matchCount).isEqualTo(3)
    }

    @Test
    @DisplayName("SYS-100: regex=true matches q as a case-insensitive regular expression")
    fun `SYS-100 regex mode matches a case-insensitive regular expression`() {
        val result = service.search("groupId\\s*=\\s*\"org\\.example\\.", regex = true)

        assertThat(result.regex).isTrue()
        assertThat(result.results.map { it.componentKey }).containsExactly("beta")
    }

    @Test
    @DisplayName("SYS-100: without regex=true, regex metacharacters are matched literally")
    fun `SYS-100 regex metacharacters are literal by default`() {
        assertThat(service.search("org.example.beta").totalComponents).isEqualTo(1)
        assertThat(service.search("org.*beta").totalComponents).isZero()
    }

    @Test
    @DisplayName("SYS-100: invalid input is rejected with IllegalArgumentException (→ HTTP 400)")
    fun `SYS-100 invalid input is rejected`() {
        assertThatThrownBy { service.search(" a ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.search("x".repeat(201)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.search("ab", limit = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.search("([a-z", regex = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid regular expression")
    }

    @Test
    @DisplayName("SYS-100: a catastrophically backtracking regex is aborted instead of pinning the thread")
    fun `SYS-100 catastrophically backtracking regex is aborted`() {
        // Deterministic: the clock jumps 1 s on every read, so the 2 s budget is spent after a few
        // deadline checks, and `a*a*a*b` over a long run of `a` needs far more than the 10 000
        // character reads between checks (cubic backtracking) — no JDK-regex internals, no real wait.
        whenever(componentManagementService.renderAllComponentsAsCode())
            .thenReturn(listOf(RenderedComponentCode("evil", "a".repeat(200) + "\n", id = UUID.randomUUID())))
        val tickingService =
            ComponentCodeSearchService(componentManagementService, componentRepository, auditLogRepository, TickingClock())

        assertThatThrownBy { tickingService.search("a*a*a*b", regex = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("too expensive")
    }

    @Test
    @DisplayName("SYS-100: the regex budget spans lines — many cheap lines cannot outrun it")
    fun `SYS-100 regex budget is enforced between lines`() {
        // Every match here is cheap (far below the in-match read interval), so only the per-line
        // check can stop it: the clock jumps 1 s per read, so the 2 s budget is gone by line 3.
        val tickingService =
            ComponentCodeSearchService(componentManagementService, componentRepository, auditLogRepository, TickingClock())

        assertThatThrownBy { tickingService.search("zzz", regex = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("too expensive")
    }

    @Test
    @DisplayName("SYS-100: a regex whose recursion exhausts the stack is rejected as invalid input, not a 500")
    fun `SYS-100 regex stack exhaustion is rejected`() {
        // java.util.regex recurses once per repetition of an alternation group, so `(a|b)*z` over a
        // long run of `a` overflows the stack long before the time budget is spent. A real clock
        // keeps the test bounded: if no overflow happened, the budget would fail it as "too expensive".
        whenever(componentManagementService.renderAllComponentsAsCode())
            .thenReturn(
                listOf(
                    RenderedComponentCode(
                        "deep",
                        "deep {\n    copyright = \"" + "a".repeat(100_000) + "\"\n}\n",
                        id = UUID.randomUUID(),
                    ),
                ),
            )
        val realClockService =
            ComponentCodeSearchService(componentManagementService, componentRepository, auditLogRepository, Clock.systemUTC())

        assertThatThrownBy { realClockService.search("(a|b)*z", regex = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("too complex")
    }

    @Test
    @DisplayName("SYS-100: the index is reused while the change stamp holds and rebuilt when it moves")
    fun `SYS-100 index is reused while the stamp holds and rebuilt when it moves`() {
        service.search("jdoe")
        service.search("alpha")
        verify(componentManagementService, times(1)).renderAllComponentsAsCode()

        stamp(count = 2, versionSum = 8, auditMaxId = 100)
        service.search("jdoe")
        verify(componentManagementService, times(2)).renderAllComponentsAsCode()

        stamp(count = 2, versionSum = 8, auditMaxId = 101)
        service.search("jdoe")
        verify(componentManagementService, times(3)).renderAllComponentsAsCode()
    }

    @Test
    @DisplayName("SYS-100: an index older than MAX_INDEX_AGE is rebuilt even with an unchanged stamp")
    fun `SYS-100 index older than MAX_INDEX_AGE is rebuilt`() {
        service.search("jdoe")
        clock.advance(ComponentCodeSearchService.MAX_INDEX_AGE.plusSeconds(1))
        service.search("jdoe")

        verify(componentManagementService, times(2)).renderAllComponentsAsCode()
    }

    @Test
    @DisplayName("SYS-100: indexLines keeps blank lines so positions equal as-code line numbers")
    fun `SYS-100 indexLines keeps blank lines so positions equal line numbers`() {
        val lines = ComponentCodeSearchService.indexLines("c {\n\n    x = 1\n}\n")

        assertThat(lines.map { it.first }).containsExactly("c {", "", "    x = 1", "}", "")
        assertThat(lines[2].second).containsExactly("c")
        assertThat(lines[0].second).isEmpty()
    }

    private fun stamp(
        count: Long,
        versionSum: Long,
        auditMaxId: Long,
    ) {
        val components = count
        val versions = versionSum
        whenever(componentRepository.findChangeStamp()).thenReturn(
            object : ComponentChangeStampRow {
                override val componentCount: Long = components
                override val maxUpdatedAt: Instant? = Instant.parse("2026-10-01T00:00:00Z")
                override val versionSum: Long = versions
            },
        )
        whenever(auditLogRepository.changeStats()).thenReturn(
            object : AuditChangeStats {
                override val maxId: Long = auditMaxId
                override val count: Long = auditMaxId
            },
        )
    }

    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    /** A clock that moves 1 s forward on every read — makes time-budget tests deterministic. */
    private class TickingClock : Clock() {
        private var now = Instant.parse("2026-10-05T10:00:00Z")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now.also { now = now.plusSeconds(1) }
    }

    private companion object {
        val ALPHA_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    }
}
