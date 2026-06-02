package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Replay of REAL captured POST request bodies.
 *
 * (PUT-with-body is parsed by [parseBodyLine] but NOT replayed — the captured
 * corpus is POST-only; the only prod PUT, `updateCache`, carries no body and is
 * dropped by the extractor. The replay loop filters to POST below.)
 *
 * Where [TraceReplayCompatTest] replays the deduplicated `(method, path)` trace
 * and SYNTHESISES bodies (`BodyFixtures`), this test replays the actual bodies
 * captured from prod by the server's request-body logging filter and dumped to
 * the `post-bodies.ndjson` sidecar in the CrsCompatTrace repo. Each line is one
 * distinct `{count, method, path, body}` record; the real `body` is POSTed
 * verbatim to both stands and the responses are diffed via the shared
 * [Comparators.compareRaw] pipeline (same `RawArraySorters` / `GavCsvComparator`
 * classification as every other compat test).
 *
 * Value over synthetic replay: `find-by-artifacts` and `detailed-versions` are
 * body-driven; a synthetic 1-element body exercises a trivial lookup, whereas
 * the real bodies are the actual multi-artifact / multi-version batches clients
 * send — so this is where schema-v2 resolution divergences actually surface.
 *
 * Activation: set `compat.bodies.file` / `COMPAT_BODIES_FILE` to the NDJSON
 * path AND both compat URLs. Skipped via `Assumptions.assumeTrue` otherwise, so
 * the rest of the suite (and branches without the bodies sidecar) are
 * unaffected. Lightweight (~hundreds of requests), so it runs inline in the
 * [1.7]/[1.8] local-stand builds, not just the manual id16.
 *
 * Endpoint-key methodology (path-only key, query as data) is identical to
 * [TraceReplayCompatTest] — see its KDoc; this test reuses
 * [TraceReplayCompatTest.parsePathAndQuery].
 */
class RealBodyReplayCompatTest : CompatibilityTestBase() {
    internal data class BodyEntry(
        val count: Long,
        val method: String,
        val path: String,
        val body: JsonNode,
    )

    /**
     * Diagnostic / operational endpoints NOT in the strict v1/v2/v3 compat
     * contract. The bodies sidecar should not contain these (the extractor drops
     * the bodyless `updateCache` PUTs), but filter defensively for parity with
     * [TraceReplayCompatTest.excludedPaths]. Exact match against the path before
     * any `?` query string.
     */
    private val excludedPaths =
        setOf(
            "/rest/api/2/components-registry/service/status",
            "/rest/api/2/components-registry/service/ping",
            "/rest/api/2/components-registry/service/updateCache",
        )

    private fun isDiagnostic(path: String): Boolean =
        path.substringBefore('?') in excludedPaths

    @Test
    fun `replay real captured request bodies`() {
        val bodiesFilePath =
            System.getProperty("compat.bodies.file")
                ?: System.getenv("COMPAT_BODIES_FILE")
        assumeTrue(
            !bodiesFilePath.isNullOrBlank(),
            "compat.bodies.file / COMPAT_BODIES_FILE not set — RealBodyReplayCompatTest skipped " +
                "(point it at post-bodies.ndjson to activate).",
        )
        val file = File(bodiesFilePath!!)
        // From here the test is CONFIGURED (the path is set), so every downstream
        // failure is HARD (`check`), not an `assumeTrue` skip. The TeamCity layer
        // only fail-fasts on file EXISTENCE; a present-but-empty/corrupt sidecar
        // must still fail the build here rather than silently skip and leave the
        // auto [1.7]/[1.8] chain green with zero real-body coverage (the rest of
        // the suite keeps the run's execution count non-zero, so the reporter's
        // zero-execution guard would not notice). This in-JVM parse is the
        // authoritative parseability gate.
        check(file.exists() && file.canRead()) {
            "compat.bodies.file=$bodiesFilePath is set but the file is not readable — a configured " +
                "real-body replay must fail, not skip. Check the CrsCompatTrace checkout / the path."
        }

        val mapper = jacksonObjectMapper()
        val (entries, droppedDiagnostic, malformed) =
            file.useLines { lines ->
                var dropped = 0
                var bad = 0
                val parsed =
                    lines
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .mapNotNull { line ->
                            val e = parseBodyLine(line, mapper)
                            if (e == null) bad++
                            e
                        }
                        .filter { e ->
                            if (isDiagnostic(e.path)) { dropped++; false } else true
                        }
                        .filter { it.method == "POST" } // only POST carries a JSON body in this corpus
                        .toList()
                Triple(parsed, dropped, bad)
            }

        // Vacuous-pass guard (reviewer P1): a present-but-empty/corrupt sidecar
        // parses to zero entries. Fail HARD — NOT an assumeTrue skip — because the
        // other compat tests keep the run's execution count non-zero, so the
        // reporter's zero-execution guard would NOT notice that the real-body
        // replay never ran, leaving the auto chain green with the new coverage
        // absent. assumeTrue-skip is reserved above for the not-configured case.
        check(entries.isNotEmpty()) {
            "compat.bodies.file=$bodiesFilePath is configured but yielded zero replayable POST " +
                "bodies (malformed=$malformed, dropped-diagnostic=$droppedDiagnostic). An empty or " +
                "corrupt sidecar must fail the build, not skip. Verify the NDJSON is the " +
                "{count,method,path,body} format from enrich-trace.py."
        }

        // Distinct stands required (Stage-2 review): identical baseline/candidate
        // URLs make every pair byte-identical → 0 diffs → vacuous GREEN on the
        // highest-signal test. Fail-HARD (not assumeTrue-skip) so a copy-paste
        // misconfiguration of COMPAT_*_URL is loud rather than silently clean.
        check(config.baselineUrl != config.candidateUrl) {
            "real-body replay: COMPAT_BASELINE_URL and COMPAT_CANDIDATE_URL are identical " +
                "(${config.baselineUrl}) — the run would be vacuously green. Point them at the two " +
                "different stands."
        }

        // Fail-hard on baseline transport failure (status==0 = IOException in
        // RawHttp). A wrong/unreachable COMPAT_BASELINE_URL makes BOTH stands miss
        // symmetrically → every diff suppressed → vacuous green. Same guard as
        // TraceReplayCompatTest.loadBodyFixtures.
        val probe = baselineRaw.get("rest/api/2/components")
        check(probe.status != 0) {
            "real-body replay: GET ${baselineRaw.baseUrl}/rest/api/2/components returned status=0 " +
                "(transport failure). Check COMPAT_BASELINE_URL is reachable from this agent — a " +
                "vacuous-green replay would otherwise look identical to a clean one. Underlying " +
                "error: ${probe.headers["X-Compat-Transport-Error"] ?: "<not captured>"}"
        }

        log.info(
            "real-body replay: ${entries.size} distinct POST bodies from $bodiesFilePath " +
                "(parallelism=${config.parallelism}, dropped $droppedDiagnostic diagnostic, " +
                "$malformed malformed lines)",
        )

        val verbose =
            (System.getProperty("compat.verbose") ?: System.getenv("COMPAT_VERBOSE"))
                ?.equals("true", ignoreCase = true) == true

        val pool = Executors.newFixedThreadPool(config.parallelism)
        val ts = java.time.Instant.now().toString()
        val processed = AtomicInteger(0)
        val withDiffs = AtomicInteger(0)
        val weightByBucket = ConcurrentHashMap<Pair<String, DiffClassifier>, Long>()
        val hungEntries = java.util.concurrent.ConcurrentLinkedQueue<BodyEntry>()
        val futureToEntry = java.util.concurrent.ConcurrentHashMap<java.util.concurrent.Future<*>, BodyEntry>()

        try {
            val futures =
                entries.map { entry ->
                    pool.submit {
                        val diffsBefore = DiffCollector.count()
                        val (baseline, candidate) =
                            try {
                                postJsonPair(entry.path, entry.body)
                            } catch (e: Exception) {
                                log.warn("transport failed for ${entry.method} ${entry.path}: ${e.message}")
                                return@submit
                            }
                        val (pathOnly, parsedQuery) = TraceReplayCompatTest.parsePathAndQuery(entry.path)
                        val endpoint = "${entry.method} $pathOnly"
                        val queryParams = parsedQuery + ("_weight" to entry.count.toString())
                        // Symmetric-failure blind spot (same as TraceReplayCompatTest): if BOTH
                        // stands return identical 4xx/5xx with identical bodies (e.g. a body
                        // referencing a since-deleted component), compareRaw records no diff and
                        // the tuple looks clean. Real prod bodies make this rarer than synthetic
                        // ones, but it remains a known gap — see TraceReplayCompatTest's
                        // BOTH_STANDS_ERRORED follow-up sketch.
                        val cats =
                            Comparators.compareRaw(
                                endpoint = endpoint,
                                pathParams = emptyMap(),
                                baseline = baseline,
                                candidate = candidate,
                                queryParams = queryParams,
                            )
                        cats.distinct().forEach { cat ->
                            weightByBucket.merge(endpoint to cat, entry.count) { a, b -> a + b }
                        }
                        val diffsAfter = DiffCollector.count()
                        val newDiffs = diffsAfter - diffsBefore
                        // Record every replayed body in the execution log so the run is
                        // PROVABLE: the reporter's proof-of-execution guard counts these, and a
                        // silently-skipped replay (missing sidecar) cannot masquerade as a clean
                        // run that actually exercised the real bodies.
                        logExecution(
                            endpoint = endpoint,
                            pathParams = emptyMap(),
                            queryParams = queryParams,
                            baseline = baseline,
                            candidate = candidate,
                            layer = "raw-body",
                            diffsBefore = diffsBefore,
                            diffsAfter = diffsAfter,
                        )
                        if (verbose) {
                            // SECURITY: body preview includes real group/artifact/version
                            // triples — gradle stdout is operator-confidential, do not paste
                            // into the public repo (feedback_redacted_identifiers).
                            log.info(
                                "← ${entry.method} ${entry.path} " +
                                    "b=${baseline.status}/${baseline.bodyBytes.size}B " +
                                    "c=${candidate.status}/${candidate.bodyBytes.size}B " +
                                    "diffs=$newDiffs cats=${cats.distinct()}",
                            )
                        }
                        val n = processed.incrementAndGet()
                        if (newDiffs > 0) withDiffs.incrementAndGet()
                        if (n % 100 == 0) {
                            log.info("real-body replay progress: $n / ${entries.size} (${withDiffs.get()} with diffs)")
                        }
                    }.also { f -> futureToEntry[f] = entry }
                }
            // NOTE (Copilot review): this walks futures in submission order, so the
            // 90s budget is wall-clock from each get(), not from submission — a
            // pathological all-hung run can approach O(N*90s). For this corpus
            // (~hundreds of bodies, far smaller than the 40k trace) it is not a
            // problem in practice; this deliberately mirrors TraceReplayCompatTest.
            // A shared future fix (ExecutorCompletionService / per-submission
            // deadline) would tighten both tests together — tracked there.
            futures.forEach { f ->
                val entry = futureToEntry[f]
                try {
                    f.get(90, TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    log.warn("HUNG (>90s, cancelling): ${entry?.method} ${entry?.path}")
                    if (entry != null) hungEntries.add(entry)
                    f.cancel(true)
                } catch (e: java.util.concurrent.CancellationException) {
                    // already cancelled
                } catch (e: Exception) {
                    log.warn("future failed for ${entry?.path ?: "?"}: ${e.message}")
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }

        val topN = weightByBucket.entries.sortedByDescending { it.value }.take(20)
        val totalReplayed = entries.size
        val totalWithDiffs = withDiffs.get()
        log.info(
            "real-body replay done: $totalReplayed bodies replayed, $totalWithDiffs produced " +
                "at least one diff, ${weightByBucket.size} unique (endpoint, category) buckets, " +
                "${hungEntries.size} hung",
        )
        if (topN.isNotEmpty()) {
            log.info("=== top-20 frequency-weighted buckets (real bodies) ===")
            topN.forEach { (key, weight) ->
                val (ep, cat) = key
                log.info("  weight=$weight  category=$cat  $ep")
            }
        }

        val reportDir = File("build/reports/compat").also { it.mkdirs() }
        val payload =
            mapOf(
                "generatedAt" to ts,
                "totals" to mapOf(
                    "bodiesReplayed" to totalReplayed,
                    "bodiesWithDiffs" to totalWithDiffs,
                    "bodiesWithoutDiffs" to (totalReplayed - totalWithDiffs),
                    "droppedDiagnostic" to droppedDiagnostic,
                    "malformedLines" to malformed,
                    "hung" to hungEntries.size,
                ),
                "topWeightedBuckets" to topN.map { (key, weight) ->
                    val (ep, cat) = key
                    mapOf("endpoint" to ep, "category" to cat.name, "weight" to weight)
                },
            )
        File(reportDir, "real-body-replay-summary.json")
            .writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
    }

    companion object {
        /**
         * Parse one `post-bodies.ndjson` line into a [BodyEntry]. Returns null on
         * malformed input (not JSON, missing/blank method or path, path not
         * starting with `/`, or absent `body`). `count` defaults to 1 when
         * absent or non-numeric. `body` is kept as a [JsonNode] and POSTed
         * verbatim, so an empty array/object body (a real edge case clients send)
         * is preserved, not rejected.
         */
        internal fun parseBodyLine(line: String, mapper: com.fasterxml.jackson.databind.ObjectMapper): BodyEntry? {
            val node =
                runCatching { mapper.readTree(line) }.getOrNull()
                    ?: return null
            if (!node.isObject) return null
            val method = node.path("method").asText("").trim().uppercase()
            if (method.isEmpty()) return null
            val path = node.path("path").asText("").trim()
            if (path.isEmpty() || !path.startsWith('/')) return null
            val body = node.get("body")
            if (body == null || body.isNull) return null
            val count = node.path("count").asLong(1L).coerceAtLeast(0L)
            return BodyEntry(count, method, path, body)
        }
    }
}
