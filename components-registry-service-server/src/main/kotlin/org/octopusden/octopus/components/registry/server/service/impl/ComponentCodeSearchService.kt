package org.octopusden.octopus.components.registry.server.service.impl

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.AsCodeMatchRange
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
     * outrun it) and, through [DeadlineCharSequence], every few thousand character reads within a
     * line. RE2 runs in time linear in the input and does a bounded amount of work per character
     * read (at most the compiled program size, capped by [MAX_PROGRAM_SIZE]), so the read check
     * also bounds a single match: no pattern can keep the thread busy without reading input.
     * A StackOverflowError is still reported as invalid input, as a backstop.
     */
    private fun lineMatcher(
        q: String,
        pattern: Pattern?,
    ): LineMatcher {
        if (pattern == null) return LineMatcher { line -> substringSpans(line, q).ifEmpty { null } }
        val budget = RegexBudget(clock.instant().plus(REGEX_TIME_BUDGET), clock)
        return LineMatcher { line ->
            budget.check()
            try {
                // A line whose only matches are empty (e.g. `x*`) still matches; it just has nothing to mark.
                // Empty matches are skipped BEFORE the cap, so they can't use up the allowance and hide a
                // real span later in the line.
                val m = pattern.matcher(DeadlineCharSequence(line, budget))
                var matched = false
                val spans = mutableListOf<IntRange>()
                while (spans.size < MAX_SPANS_PER_LINE && m.find()) {
                    matched = true
                    if (m.end() > m.start()) spans += m.start() until m.end()
                }
                spans.takeIf { matched }
            } catch (e: StackOverflowError) {
                throw IllegalArgumentException(TOO_COMPLEX_MESSAGE, e)
            }
        }
    }

    /** The hit for [doc], or `null` when no line matches. `matches` is capped; `matchCount` is not. */
    private fun matchDocument(
        doc: SearchDocument,
        matcher: LineMatcher,
        maxMatchesPerComponent: Int,
    ): AsCodeSearchHit? {
        val matching =
            doc.lines.withIndex().mapNotNull { (i, line) ->
                if (line.text.isEmpty()) return@mapNotNull null
                matcher.spans(line.text)?.let { spans -> Triple(i, line, spans) }
            }
        if (matching.isEmpty()) return null
        val matches =
            matching
                .take(maxMatchesPerComponent)
                .map { (i, line, spans) -> toSearchLine(i + 1, line, spans) }
        return AsCodeSearchHit(doc.id, doc.componentKey, doc.archived, matching.size, matches)
    }

    /**
     * Matching spans of one line, inclusive [IntRange]s in the coordinates of the indexed (indented)
     * line; `null` when the line does not match at all.
     */
    private fun interface LineMatcher {
        fun spans(line: String): List<IntRange>?
    }

    /**
     * The API line: [AsCodeSearchLine.text] drops the indentation, so the spans are shifted by it
     * and clipped to the trimmed text (a match on indentation alone leaves no span).
     */
    private fun toSearchLine(
        lineNumber: Int,
        line: IndexedLine,
        spans: List<IntRange>,
    ): AsCodeSearchLine {
        val indent = line.text.length - line.text.trimStart().length
        val text = line.text.trim()
        val ranges =
            spans.mapNotNull { span ->
                val start = (span.first - indent).coerceAtLeast(0)
                val end = (span.last + 1 - indent).coerceAtMost(text.length)
                AsCodeMatchRange(start, end).takeIf { start < end }
            }
        return AsCodeSearchLine(line = lineNumber, text = text, path = line.path, ranges = ranges)
    }

    /**
     * RE2 syntax (no lookarounds or backreferences, repetition counts up to 1000): it guarantees
     * linear-time matching, which the JDK engine cannot. Nested repetitions still multiply the
     * compiled program, and with it the work per character and the matcher's recursion depth, so
     * the program size is capped too. RE2/J builds the whole program while compiling (a 31-character
     * triple-nested `{1000}` exhausts the heap there), so a cheap estimate rejects such patterns
     * BEFORE compiling; the exact size is checked after.
     */
    private fun compilePattern(q: String): Pattern {
        require(estimateProgramSize(q) <= MAX_ESTIMATED_PROGRAM_SIZE) { TOO_COMPLEX_MESSAGE }
        val pattern =
            try {
                Pattern.compile(q, Pattern.CASE_INSENSITIVE)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("Invalid regular expression: ${e.description}", e)
            }
        require(pattern.programSize() <= MAX_PROGRAM_SIZE) { TOO_COMPLEX_MESSAGE }
        return pattern
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
     * A CharSequence view that charges every read to the request's [RegexBudget]. The RE2 matcher
     * reads input exclusively through [get], so this bounds the work within a single line; the clock
     * itself is consulted only every few thousand reads.
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

        /** Compiled RE2 instructions; bounds the per-character work and the matcher's recursion depth. */
        const val MAX_PROGRAM_SIZE = 2000

        /** Pre-compile guard on [estimateProgramSize]: generous, it only has to keep compiling cheap. */
        private const val MAX_ESTIMATED_PROGRAM_SIZE = 20_000L

        /** A group's capture-start and capture-end instructions; also the program's own fail and match. */
        private const val CAPTURE_SIZE = 2L

        /** `|`, `*`, `+`, `?`: the split instruction and the empty match of a branch that may be empty. */
        private const val SPLIT_SIZE = 2L

        /** `(?i)`, `(?-s)`, …: sets flags, compiles to nothing, so a following repetition applies to what precedes it. */
        private val FLAG_GROUP = Regex("""\(\?[imsU-]*\)""")

        /** `(?:`, `(?i:`, …: opens a group without capture instructions. */
        private val NON_CAPTURING_GROUP = Regex("""\(\?[imsU-]*:""")
        private val REPEAT = Regex("""\{(\d+)(,(\d*))?}""")

        /** Highlight spans reported per line; matching beyond this many spans adds nothing to read. */
        const val MAX_SPANS_PER_LINE = 50
        private const val TOO_EXPENSIVE_MESSAGE = "Regular expression is too expensive to evaluate; simplify the pattern"
        private const val TOO_COMPLEX_MESSAGE = "Regular expression is too complex to evaluate; simplify the pattern"

        /**
         * An upper-bound estimate of the RE2 program [q] compiles to, from the pattern text alone: every
         * atom counts 1, `|` / `*` / `+` / `?` 2, an empty pattern or group 1, a sequence or alternation
         * adds, and a counted repetition `{n}` / `{n,m}` repeats the atom or group before it, plus one
         * alternation instruction, as many times as its largest count (`{n,}`: n + 1 times, plus two
         * splits). The program adds 2, a capturing group adds 2 for its capture instructions, `\Q..\E`
         * counts each quoted character (a repetition after it repeats the last one; an empty quote and a
         * flag group `(?i)` are skipped), other escapes (incl. `\p{..}`, `\x{..}`) and character classes
         * are single atoms. Saturates instead of overflowing.
         */
        internal fun estimateProgramSize(q: String): Long {
            val estimate = ProgramSizeEstimate()
            var i = 0
            while (i < q.length) i = estimate.consume(q, i)
            return estimate.total()
        }

        /** Running state of [estimateProgramSize]: one size per open group, innermost last. */
        private class ProgramSizeEstimate {
            private val sums = ArrayDeque(listOf(0L))

            /** Whether each open group captures, parallel to [sums] (the pattern itself does not). */
            private val captures = ArrayDeque(listOf(false))

            /** Size of the atom or group a following repetition applies to. */
            private var last = 0L

            /** Consumes the token at [i]; returns the index just past it. */
            fun consume(
                q: String,
                i: Int,
            ): Int =
                when (q[i]) {
                    '\\' -> escape(q, i)
                    '[' -> past(skipClass(q, i)) { atom(1) }
                    '(' -> open(q, i)
                    ')' -> past(i + 1) { close() }
                    '|' -> past(i + 1) { atom(SPLIT_SIZE) }
                    in "*+?" -> past(i + 1) { quantify() }
                    '{' -> repeat(q, i)
                    else -> past(i + 1) { atom(1) }
                }

            /** Runs [step] for the token ending at [end]; returns [end]. */
            private inline fun past(
                end: Int,
                step: () -> Unit,
            ): Int {
                step()
                return end
            }

            /** A flag group `(?i)` compiles to nothing; `(?:` / `(?i:` opens a group without capture, `(` one with. */
            private fun open(
                q: String,
                i: Int,
            ): Int {
                FLAG_GROUP.matchAt(q, i)?.let { return i + it.value.length }
                val nonCapturing = NON_CAPTURING_GROUP.matchAt(q, i)
                sums.addLast(0L)
                captures.addLast(nonCapturing == null)
                return i + (nonCapturing?.value?.length ?: 1)
            }

            private fun close() {
                val nested = sums.size > 1
                val size = (if (nested) sums.removeLast() else 0L).coerceAtLeast(1)
                atom(if (!nested || captures.removeLast()) saturatingAdd(size, CAPTURE_SIZE) else size)
            }

            // An empty pattern or group still compiles to one empty-match instruction.
            fun total(): Long = saturatingAdd(sums.fold(0L, ::saturatingAdd).coerceAtLeast(1), CAPTURE_SIZE)

            private fun atom(size: Long) {
                grow(size)
                last = size
            }

            /**
             * An escape is one atom; a `\Q..\E` quote adds each of its characters, but a following
             * repetition applies to its last character only, and an empty quote leaves the target as it was.
             */
            private fun escape(
                q: String,
                i: Int,
            ): Int {
                val end = skipEscape(q, i)
                val quoted = quotedLength(q, i, end)
                when {
                    quoted == null -> atom(1)
                    quoted > 0 -> {
                        grow(quoted)
                        last = 1
                    }
                }
                return end
            }

            /** `*` / `+` / `?` wraps the preceding atom, which a following flag-group repetition repeats whole. */
            private fun quantify() {
                grow(SPLIT_SIZE)
                last = saturatingAdd(last, SPLIT_SIZE)
            }

            private fun grow(size: Long) = sums.addLast(saturatingAdd(sums.removeLast(), size))

            /**
             * A counted repetition repeats the preceding atom and an alternation by its largest count, `{n,}` the
             * atom and two splits n + 1 times; any other `{` is a literal.
             */
            private fun repeat(
                q: String,
                i: Int,
            ): Int {
                val match = REPEAT.matchAt(q, i) ?: return (i + 1).also { atom(1) }
                val (min, comma, max) = match.destructured
                // `{n,}` compiles to n copies and a star, which takes two splits around a nullable atom.
                val openEnded = comma.isNotEmpty() && max.isEmpty()
                val count = (if (openEnded) min.toLongOrNull()?.let { saturatingAdd(it, 1) } else max.ifEmpty { min }.toLongOrNull())
                    ?.coerceAtLeast(1) ?: Long.MAX_VALUE
                val repeated = saturatingMultiply(saturatingAdd(last, if (openEnded) SPLIT_SIZE else 1), count)
                grow(repeated - last)
                last = repeated
                return i + match.value.length
            }
        }

        /** Literal characters of a `\Q..\E` escape spanning [start] until [end]; `null` for any other escape. */
        private fun quotedLength(
            q: String,
            start: Int,
            end: Int,
        ): Long? {
            if (!q.startsWith("\\Q", start)) return null
            val closed = end - 2 >= start + 2 && q.startsWith("\\E", end - 2)
            return (end - start - 2 - (if (closed) 2 else 0)).toLong()
        }

        /** Index just past the escape starting at [start] (`\x`, `\p{..}`, `\x{..}`, `\Q..\E`). */
        private fun skipEscape(
            q: String,
            start: Int,
        ): Int {
            val next = start + 1
            return when {
                next >= q.length -> q.length
                q[next] == 'Q' -> q.indexOf("\\E", next).let { if (it < 0) q.length else it + 2 }
                q[next] in "pPx" && q.startsWith("{", next + 1) -> q.indexOf('}', next).let { if (it < 0) q.length else it + 1 }
                else -> next + 1
            }
        }

        /** Index just past the character class starting at [start]; a leading `]` or `^]` is literal. */
        private fun skipClass(
            q: String,
            start: Int,
        ): Int {
            var i = start + 1
            if (i < q.length && q[i] == '^') i++
            if (i < q.length && q[i] == ']') i++
            while (i < q.length && q[i] != ']') {
                i =
                    when {
                        q[i] == '\\' -> i + 2
                        q.startsWith("[:", i) -> q.indexOf(":]", i).let { if (it < 0) q.length else it + 2 }
                        else -> i + 1
                    }
            }
            return (i + 1).coerceAtMost(q.length)
        }

        private fun saturatingAdd(
            a: Long,
            b: Long,
        ): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

        private fun saturatingMultiply(
            a: Long,
            b: Long,
        ): Long = if (a != 0L && b > Long.MAX_VALUE / a) Long.MAX_VALUE else a * b

        /** Case-insensitive, non-overlapping occurrences of [q] in [line] (inclusive ranges), capped. */
        internal fun substringSpans(
            line: String,
            q: String,
        ): List<IntRange> {
            val spans = mutableListOf<IntRange>()
            var at = line.indexOf(q, ignoreCase = true)
            while (at >= 0 && spans.size < MAX_SPANS_PER_LINE) {
                spans += at until at + q.length
                at = line.indexOf(q, at + q.length, ignoreCase = true)
            }
            return spans
        }

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
