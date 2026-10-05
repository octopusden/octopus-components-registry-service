package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.AsCodeSearchHit
import org.octopusden.octopus.components.registry.server.dto.v4.AsCodeSearchLine
import org.octopusden.octopus.components.registry.server.dto.v4.AsCodeSearchResponse
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.RenderedComponentCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.PatternSyntaxException

/**
 * Global text search over the components' as-code view (SYS-098) — the DB-era replacement for
 * grepping the Groovy DSL files. The corpus is every component's FULL as-code render
 * ([ComponentManagementService.renderAllComponentsAsCode]), so anything the as-code view shows —
 * artifact patterns, version ranges, VCS URLs, Jira keys, docker images, people — is searchable
 * without a per-field query.
 *
 * **Index.** Rendering the whole registry is too heavy to do per request, so the rendered lines
 * are kept in memory and rebuilt lazily. Before each search a cheap change stamp is read from
 * the shared DB (component count / max `updatedAt` / `version` sum, plus the SYS-094 audit
 * `changeStats`); a stamp that differs from the one the index was built at triggers a rebuild.
 * Because the stamp comes from the DB, an edit on one pod is seen by every pod. Writes that touch
 * neither components nor the audit log (e.g. TeamCity version-line sync, SYS-051) are covered by
 * [MAX_INDEX_AGE]: an index older than that is rebuilt regardless of the stamp.
 *
 * The stamp is read BEFORE rendering, so a write that lands mid-rebuild leaves the index tagged
 * with the older stamp — the next search sees the newer stamp and rebuilds again (never the
 * reverse, which would pin stale text under a fresh stamp).
 */
@ConditionalOnDatabaseEnabled
@Service
class ComponentCodeSearchService(
    private val componentManagementService: ComponentManagementService,
    private val componentRepository: ComponentRepository,
    private val auditLogRepository: AuditLogRepository,
    // Defaulted so unit tests can drive index ageing; Spring uses the system clock.
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ComponentCodeSearchService::class.java)
    private val index = AtomicReference<SearchIndex?>(null)
    private val rebuildLock = Any()

    fun search(
        query: String,
        regex: Boolean = false,
        archived: Boolean? = null,
        limit: Int = DEFAULT_LIMIT,
        maxMatchesPerComponent: Int = DEFAULT_MAX_MATCHES_PER_COMPONENT,
    ): AsCodeSearchResponse {
        val q = query.trim()
        require(q.length >= MIN_QUERY_LENGTH) { "q must be at least $MIN_QUERY_LENGTH characters" }
        require(q.length <= MAX_QUERY_LENGTH) { "q must be at most $MAX_QUERY_LENGTH characters" }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        require(maxMatchesPerComponent in 1..MAX_MATCHES_PER_COMPONENT) {
            "maxMatchesPerComponent must be between 1 and $MAX_MATCHES_PER_COMPONENT"
        }
        // Compile (and reject) the pattern before a possibly expensive index rebuild.
        val pattern = if (regex) compilePattern(q) else null
        val documents = currentIndex().documents
        // The time budget starts AFTER the index is ready, so a rebuild never counts against it.
        val deadline = clock.instant().plus(REGEX_TIME_BUDGET)
        val matcher: (String) -> Boolean =
            if (pattern != null) {
                // Lines are matched through a deadline-checking CharSequence so a pathological
                // (catastrophically backtracking) pattern fails fast instead of pinning a thread.
                { line -> pattern.containsMatchIn(DeadlineCharSequence(line, deadline, clock)) }
            } else {
                { line -> line.contains(q, ignoreCase = true) }
            }

        val hits = mutableListOf<AsCodeSearchHit>()
        var total = 0
        for (doc in documents.filter { archived == null || it.archived == archived }) {
            val matches = mutableListOf<AsCodeSearchLine>()
            var matchCount = 0
            doc.lines.forEachIndexed { i, line ->
                if (line.text.isNotEmpty() && matcher(line.text)) {
                    matchCount++
                    if (matches.size < maxMatchesPerComponent) {
                        matches += AsCodeSearchLine(line = i + 1, text = line.text.trim(), path = line.path)
                    }
                }
            }
            if (matchCount > 0) {
                total++
                if (hits.size < limit) hits += AsCodeSearchHit(doc.componentKey, doc.archived, matchCount, matches)
            }
        }
        return AsCodeSearchResponse(
            query = q,
            regex = regex,
            totalComponents = total,
            truncated = total > hits.size,
            results = hits,
        )
    }

    private fun compilePattern(q: String): Regex =
        try {
            Regex(q, RegexOption.IGNORE_CASE)
        } catch (e: PatternSyntaxException) {
            throw IllegalArgumentException("Invalid regular expression: ${e.description}", e)
        }

    private fun currentIndex(): SearchIndex {
        val stamp = readStamp()
        fresh(index.get(), stamp)?.let { return it }
        synchronized(rebuildLock) {
            fresh(index.get(), stamp)?.let { return it }
            val started = System.nanoTime()
            val rebuilt =
                SearchIndex(
                    stamp = stamp,
                    builtAt = clock.instant(),
                    documents = componentManagementService.renderAllComponentsAsCode().map { toDocument(it) },
                )
            index.set(rebuilt)
            log.info(
                "As-code search index rebuilt: {} components in {} ms",
                rebuilt.documents.size,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
            )
            return rebuilt
        }
    }

    private fun fresh(
        candidate: SearchIndex?,
        stamp: String,
    ): SearchIndex? =
        candidate?.takeIf {
            it.stamp == stamp && Duration.between(it.builtAt, clock.instant()) < MAX_INDEX_AGE
        }

    private fun readStamp(): String {
        val components = componentRepository.findChangeStamp()
        val audit = auditLogRepository.changeStats()
        return "${components.componentCount}.${components.maxUpdatedAt}.${components.versionSum}.${audit.maxId}.${audit.count}"
    }

    private class SearchIndex(
        val stamp: String,
        val builtAt: Instant,
        val documents: List<SearchDocument>,
    )

    private class SearchDocument(
        val componentKey: String,
        val archived: Boolean,
        val lines: List<IndexedLine>,
    )

    private class IndexedLine(
        val text: String,
        val path: List<String>,
    )

    /**
     * A CharSequence view that aborts regex evaluation once [deadline] passes. `java.util.regex`
     * reads input exclusively through [get], so checking there bounds even exponential backtracking.
     * The clock is consulted every [CHECK_INTERVAL] reads to keep the overhead negligible.
     */
    private class DeadlineCharSequence(
        private val delegate: CharSequence,
        private val deadline: Instant,
        private val clock: Clock,
    ) : CharSequence {
        private var reads = 0

        override val length: Int get() = delegate.length

        override fun get(index: Int): Char {
            require(++reads % CHECK_INTERVAL != 0 || !clock.instant().isAfter(deadline)) {
                "Regular expression is too expensive to evaluate; simplify the pattern"
            }
            return delegate[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = DeadlineCharSequence(delegate.subSequence(startIndex, endIndex), deadline, clock)

        override fun toString(): String = delegate.toString()

        private companion object {
            const val CHECK_INTERVAL = 10_000
        }
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 200
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
        const val DEFAULT_MAX_MATCHES_PER_COMPONENT = 20
        const val MAX_MATCHES_PER_COMPONENT = 1000
        val MAX_INDEX_AGE: Duration = Duration.ofMinutes(5)
        private val REGEX_TIME_BUDGET: Duration = Duration.ofSeconds(2)

        /**
         * Split a rendered document into lines, tagging each with the block headers that enclose
         * it (`header {` opens a block, a lone `}` closes one — the `CodeBuilder` layout). A line
         * that opens a block carries the headers of its parents, not its own.
         */
        internal fun indexLines(body: String): List<Pair<String, List<String>>> {
            val stack = ArrayDeque<String>()
            // Blank lines are kept (never dropped) so list position == line number in the as-code view.
            return body.lines().map { raw ->
                val text = raw.trimEnd()
                val trimmed = text.trim()
                val path = stack.toList()
                when {
                    trimmed.endsWith(" {") -> stack.addLast(trimmed.removeSuffix(" {"))
                    trimmed == "}" -> stack.removeLastOrNull()
                }
                text to path
            }
        }

        private fun toDocument(rendered: RenderedComponentCode): SearchDocument =
            SearchDocument(
                componentKey = rendered.componentKey,
                archived = rendered.archived,
                lines = indexLines(rendered.body).map { (text, path) -> IndexedLine(text, path) },
            )
    }
}
