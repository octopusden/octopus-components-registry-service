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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.PatternSyntaxException

/**
 * Global text search over the components' as-code view (SYS-100) — the DB-era replacement for
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
        validate(q, limit, maxMatchesPerComponent)
        // Compile (and reject) the pattern before a possibly expensive index rebuild.
        val pattern = if (regex) compilePattern(q) else null
        val documents = currentIndex().documents
        val matcher = lineMatcher(q, pattern)

        val hits =
            documents
                .filter { archived == null || it.archived == archived }
                .mapNotNull { doc -> matchDocument(doc, matcher, maxMatchesPerComponent) }
        return AsCodeSearchResponse(
            query = q,
            regex = regex,
            totalComponents = hits.size,
            truncated = hits.size > limit,
            results = hits.take(limit),
        )
    }

    private fun validate(
        q: String,
        limit: Int,
        maxMatchesPerComponent: Int,
    ) {
        require(q.length >= MIN_QUERY_LENGTH) { "q must be at least $MIN_QUERY_LENGTH characters" }
        require(q.length <= MAX_QUERY_LENGTH) { "q must be at most $MAX_QUERY_LENGTH characters" }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        require(maxMatchesPerComponent in 1..MAX_MATCHES_PER_COMPONENT) {
            "maxMatchesPerComponent must be between 1 and $MAX_MATCHES_PER_COMPONENT"
        }
    }

    /**
     * Case-insensitive substring matcher, or — for [pattern] — a regex matcher bounded by one
     * [RegexBudget] for the whole request. The budget starts now, i.e. AFTER the index is ready, so
     * a rebuild never counts against it. It is checked before every line (many cheap lines must not
     * outrun it) and, through [DeadlineCharSequence], inside a single expensive match (catastrophic
     * backtracking). A pattern whose recursion exhausts the stack on a long line is reported as
     * invalid input too, instead of escaping as a StackOverflowError.
     */
    private fun lineMatcher(
        q: String,
        pattern: Regex?,
    ): (String) -> Boolean {
        if (pattern == null) return { line -> line.contains(q, ignoreCase = true) }
        val budget = RegexBudget(clock.instant().plus(REGEX_TIME_BUDGET), clock)
        return { line ->
            budget.check()
            try {
                pattern.containsMatchIn(DeadlineCharSequence(line, budget))
            } catch (e: StackOverflowError) {
                throw IllegalArgumentException(TOO_COMPLEX_MESSAGE, e)
            }
        }
    }

    /** The hit for [doc], or `null` when no line matches. `matches` is capped; `matchCount` is not. */
    private fun matchDocument(
        doc: SearchDocument,
        matcher: (String) -> Boolean,
        maxMatchesPerComponent: Int,
    ): AsCodeSearchHit? {
        val matching =
            doc.lines.withIndex().filter { (_, line) -> line.text.isNotEmpty() && matcher(line.text) }
        if (matching.isEmpty()) return null
        val matches =
            matching
                .take(maxMatchesPerComponent)
                .map { (i, line) -> AsCodeSearchLine(line = i + 1, text = line.text.trim(), path = line.path) }
        return AsCodeSearchHit(doc.id, doc.componentKey, doc.archived, matching.size, matches)
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
        val id: UUID,
        val componentKey: String,
        val archived: Boolean,
        val lines: List<IndexedLine>,
    )

    private class IndexedLine(
        val text: String,
        val path: List<String>,
    )

    /**
     * The regex time budget of ONE search request, shared by every line it matches. The read
     * counter is shared too, so the every-[CHECK_INTERVAL]-reads clock check spans lines instead of
     * restarting at zero on each one. Confined to the request thread, so no synchronization.
     */
    private class RegexBudget(
        private val deadline: Instant,
        private val clock: Clock,
    ) {
        private var reads = 0L

        fun check() = require(!clock.instant().isAfter(deadline)) { TOO_EXPENSIVE_MESSAGE }

        fun onRead() {
            if (++reads % CHECK_INTERVAL == 0L) check()
        }

        private companion object {
            const val CHECK_INTERVAL = 10_000L
        }
    }

    /**
     * A CharSequence view that charges every read to the request's [RegexBudget]. `java.util.regex`
     * reads input exclusively through [get], so this bounds even exponential backtracking within a
     * single line; the clock itself is consulted only every few thousand reads.
     */
    private class DeadlineCharSequence(
        private val delegate: CharSequence,
        private val budget: RegexBudget,
    ) : CharSequence {
        override val length: Int get() = delegate.length

        override fun get(index: Int): Char {
            budget.onRead()
            return delegate[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = DeadlineCharSequence(delegate.subSequence(startIndex, endIndex), budget)

        override fun toString(): String = delegate.toString()
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
        private const val TOO_EXPENSIVE_MESSAGE = "Regular expression is too expensive to evaluate; simplify the pattern"
        private const val TOO_COMPLEX_MESSAGE = "Regular expression is too complex to evaluate; simplify the pattern"

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
                id = checkNotNull(rendered.id) { "Rendered component '${rendered.componentKey}' has no id" },
                componentKey = rendered.componentKey,
                archived = rendered.archived,
                lines = indexLines(rendered.body).map { (text, path) -> IndexedLine(text, path) },
            )
    }
}
