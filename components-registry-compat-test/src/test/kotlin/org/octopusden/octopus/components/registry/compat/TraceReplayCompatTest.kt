package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production-trace replay (TD-008 implementation).
 *
 * Reads a deduplicated trace file (`<count>\t<METHOD>\t<path>` per line) from
 * `$COMPAT_TRACE_FILE` and replays each tuple against both stands via the
 * existing raw-layer compare pipeline ([Comparators.compareRaw]). All the
 * regression-guard infrastructure already in place — `RawArraySorters`,
 * `GavCsvComparator`, the typed-vs-raw classification — applies automatically.
 *
 * Single `@Test` method (not `@ParameterizedTest`): 20 k+ parameterized
 * invocations would add tens of seconds of JUnit-framework overhead per run.
 * The aggregated DiffCollector report carries per-tuple diagnostics in its
 * ndjson, so per-tuple JUnit visibility is unnecessary.
 *
 * Skipped via `Assumptions.assumeTrue` when `COMPAT_TRACE_FILE` is unset, so
 * the regular compat job is unaffected. Activate by setting both compat URLs
 * AND the trace-file path.
 *
 * ## Endpoint key vs. query string (methodology note)
 *
 * The `endpoint` string passed to [Comparators.compareRaw] is the templated /
 * path-only form (`"<METHOD> <path-without-query>"`) — NOT the raw
 * `entry.path`, which includes any `?key=value&…` suffix. The query string,
 * when present, is parsed into the `queryParams` map alongside the synthetic
 * `_weight` bucket-counter. This matters because `RawArraySorters.stableSorted`
 * is keyed on the endpoint string by EXACT match: if the query were left
 * embedded in the endpoint, the sorter would miss its registration and any
 * unordered-array comparisons would emit positional `STRUCTURAL_DIFF` noise.
 *
 * Maintainers: keep the split. Do not concatenate `entry.path` (raw) into the
 * `endpoint` parameter. Endpoint = path template; query = data.
 */
class TraceReplayCompatTest : CompatibilityTestBase() {
    private data class TraceEntry(val count: Long, val method: String, val path: String)

    /**
     * Diagnostic / operational endpoints that are NOT part of the strict v1/v2/v3
     * backward-compat contract (see the §"Compat surface scope" of the addendum
     * to `~/.claude/plans/async-stirring-koala.md` and `ServiceStatusV2CompatTest.kt`
     * lines 15-18). The trace contains real production hits on these — most are
     * heartbeat polls from Portal / Prometheus / Kubernetes — and replaying them
     * is wasted work plus produces noise (additive nullable fields in
     * `ServiceStatusDTO` surface as KEY_MISSING on the older baseline).
     *
     * The filter is path-prefix exact-match against the path BEFORE the `?` query
     * string. Future diagnostic additions go here.
     */
    // Exact-match (NOT prefix-match) — paths like `/.../service/status/sub` are NOT
    // excluded. Renamed from `excludedPathPrefixes` after Stage-2 review flagged the
    // name/impl mismatch. If prefix-matching is ever wanted, switch to `startsWith`
    // explicitly so the contract is visible at the call site.
    private val excludedPaths =
        listOf(
            "/rest/api/2/components-registry/service/status",
            "/rest/api/2/components-registry/service/ping",
            "/rest/api/2/components-registry/service/updateCache",
        )

    private fun isDiagnostic(path: String): Boolean {
        val pathOnly = path.substringBefore('?')
        return excludedPaths.any { pathOnly == it }
    }

    /**
     * Body fixtures synthesized at run-start so POST endpoints in the trace are
     * exercised with valid bodies (the access-log capture format doesn't include
     * request bodies). Strategy:
     *
     *  - `singleArtifact`: pick a known-existing GAV from baseline's
     *    `GET /v3/components` listing, combined with a real release from RMS.
     *    Used for `POST /v2/components/find-by-artifact`.
     *  - `batchArtifacts`: same approach, 5 distinct components in one list.
     *    Used for `POST /v3/components/find-by-artifacts` (and the v2
     *    `findByArtifacts` alias).
     *  - `versionsFor(componentId)`: lazy per-component lookup — file map
     *    when `compat.versions.file` is set, else RMS (see VersionSampler).
     *    Used for `POST /v2/components/{c}/detailed-versions`.
     *
     * Without these fixtures the trace replayer falls back to `{}` empty body,
     * which trips per-stand validation handlers that emit DIFFERENT error-body
     * shapes (V1 returns Spring default `{timestamp, status, error, path}`;
     * schema-v2 returns custom `{errorMessage}` via `ErrorResponse`). That
     * difference is real but isn't what we're trying to measure — we want to
     * exercise the actual lookup code path, not the validation error path.
     */
    private data class BodyFixtures(
        val singleArtifact: ArtifactDependency?,
        val batchArtifacts: List<ArtifactDependency>,
        val versionsCache: ConcurrentHashMap<String, List<String>>,
        val rmsUrl: String?,
    ) {
        fun versionsFor(componentId: String): List<String> =
            versionsCache.computeIfAbsent(componentId) { id ->
                VersionSampler.versionsFor(id, limit = 3, rmsUrl = rmsUrl)
            }
    }

    private fun loadBodyFixtures(): BodyFixtures {
        log.info("body-fixtures: fetching /v2/components for GAV discovery…")
        val resp = baselineRaw.get("rest/api/2/components")
        // Distinguish transport failure (`status == 0` — see `RawHttp.kt`: catches
        // `IOException` and returns synthetic 0) from a legitimate empty/error
        // response. With a typo in `COMPAT_BASELINE_URL`, both `loadBodyFixtures`
        // AND the trace replay itself would symmetrically miss the same wrong
        // host and produce a vacuously-green run. Fail-hard so the operator
        // gets a red build with a clear cause, not a SKIPPED test downstream.
        check(resp.status != 0) {
            "body-fixtures: GET ${baselineRaw.baseUrl}/rest/api/2/components returned " +
                "status=0 (transport failure). Check COMPAT_BASELINE_URL is reachable from this " +
                "agent — a vacuous-green replay would otherwise look identical to a clean one. " +
                "Underlying error: ${resp.headers["X-Compat-Transport-Error"] ?: "<not captured>"}"
        }
        // Wire shape on V1: { "components": [ {id, distribution: {GAV: "g:a:p,..."}, ...}, ... ] }
        // The field is `GAV` (UPPERCASE) on the wire — V1's @JsonProperty annotation.
        // `/v3/components` is unsuitable: V1 returns `distribution: null` at the wrapper level.
        val triples =
            if (resp.status in 200..299 && resp.json != null) {
                val arr = resp.json.path("components")
                arr.mapNotNull { c ->
                    val id = c.path("id").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val gavCsv = c.path("distribution").path("GAV").asText("")
                    if (gavCsv.isBlank()) return@mapNotNull null
                    val firstGav = gavCsv.split(',').firstOrNull()?.trim().orEmpty()
                    val parts = firstGav.split(':')
                    if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
                    Triple(id, parts[0], parts[1])
                }
            } else {
                log.warn("body-fixtures: /v2/components returned status=${resp.status}; bodies will fall back to empty")
                emptyList()
            }
        log.info("body-fixtures: ${triples.size} components have a usable GAV")

        val rmsUrl = config.rmsUrl
        val singleArtifact =
            triples.firstNotNullOfOrNull { (id, group, artifact) ->
                val version = VersionSampler.versionsFor(id, limit = 1, rmsUrl = rmsUrl).firstOrNull()
                if (version != null) ArtifactDependency(group, artifact, version) else null
            }
        val batchArtifacts =
            triples
                .asSequence()
                .mapNotNull { (id, group, artifact) ->
                    val version = VersionSampler.versionsFor(id, limit = 1, rmsUrl = rmsUrl).firstOrNull()
                    if (version != null) ArtifactDependency(group, artifact, version) else null
                }
                .take(5)
                .toList()
        log.info(
            "body-fixtures: singleArtifact=${singleArtifact != null}, " +
                "batchArtifacts.size=${batchArtifacts.size}",
        )
        return BodyFixtures(singleArtifact, batchArtifacts, ConcurrentHashMap(), rmsUrl)
    }

    private fun pathOnlyForRouting(path: String): String = path.substringBefore('?')

    private fun extractComponentIdForDetailedVersions(pathOnly: String): String? {
        // /rest/api/2/components/<id>/detailed-versions
        val match = Regex("/rest/api/2/components/([^/]+)/detailed-versions$").find(pathOnly)
        return match?.groupValues?.getOrNull(1)
    }

    /**
     * Pick a request body for a POST endpoint:
     *  - `/find-by-artifact` → a single [ArtifactDependency] (from fixtures; well-formed dummy otherwise).
     *  - `/find-by-artifacts` (v3) and the legacy `/findByArtifacts` alias → list of `ArtifactDependency`.
     *  - `/find-by-docker-images` (v3) → empty `Set<Image>` (`[]`); both stands return empty result.
     *  - `/{c}/detailed-versions` → `VersionRequest = {"versions": [...]}` (wrapper object, NOT a bare list).
     *  - anything else → empty `{}` (we don't have a better guess from the trace alone).
     *
     * Each body MUST be well-formed JSON the controller will deserialise without 400. A `{}` against
     * a strongly-typed endpoint produced ~1445 STRUCTURAL_DIFF in the killed 2026-05-17 run because
     * V1 and schema-v2 emit different error-response shapes (V1 spring-default `{timestamp,...}` vs
     * schema-v2 `{errorMessage}`). Sending a valid body routes both stands through the same lookup
     * path so the residual shrinks to real business diffs.
     */
    private fun chooseBody(rawPath: String, fixtures: BodyFixtures): Any {
        val pathOnly = pathOnlyForRouting(rawPath)
        return when {
            pathOnly.endsWith("/find-by-artifact") ->
                fixtures.singleArtifact ?: mapOf("group" to "x", "name" to "x", "version" to "x")
            pathOnly.endsWith("/find-by-artifacts") || pathOnly.endsWith("/findByArtifacts") ->
                fixtures.batchArtifacts.ifEmpty { listOf<Any>() }
            pathOnly.endsWith("/find-by-docker-images") ->
                listOf<Any>()
            pathOnly.endsWith("/detailed-versions") -> {
                val componentId = extractComponentIdForDetailedVersions(pathOnly)
                val versions = componentId?.let { fixtures.versionsFor(it) } ?: emptyList()
                mapOf("versions" to versions)
            }
            // Empty map → Jackson serialises to `{}`. Returning the String literal `"{}"`
            // would be double-encoded (Jackson would write `"\"{}\""`) and most stands
            // reject that with 400/415 symmetrically — same outcome but for the wrong
            // reason. emptyMap keeps the fallback well-formed for any future unknown
            // POST endpoint we haven't covered yet.
            else -> emptyMap<String, Any>()
        }
    }

    @Test
    fun `replay deduplicated production trace`() {
        val traceFilePath =
            System.getProperty("compat.trace.file")
                ?: System.getenv("COMPAT_TRACE_FILE")
        assumeTrue(
            !traceFilePath.isNullOrBlank(),
            "compat.trace.file / COMPAT_TRACE_FILE not set — TraceReplayCompatTest skipped " +
                "(set the env var to a deduplicated <count>\\t<METHOD>\\t<path> file to activate).",
        )

        val file = File(traceFilePath!!)
        assumeTrue(file.exists() && file.canRead(), "trace file not readable: $traceFilePath")

        val (entries, droppedDiagnostic) =
            file
                .useLines { lines ->
                    val parsed =
                        lines
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .mapNotNull { parse(it) }
                            .toList()
                    val kept = parsed.filterNot { isDiagnostic(it.path) }
                    kept to (parsed.size - kept.size)
                }
        assumeTrue(entries.isNotEmpty(), "trace file is empty: $traceFilePath")

        log.info(
            "replaying ${entries.size} unique (method, path) tuples from $traceFilePath " +
                "(parallelism=${config.parallelism}, " +
                "dropped $droppedDiagnostic diagnostic-surface tuples)",
        )

        // Discover request-body fixtures once, before the parallel replay starts.
        // See the BodyFixtures KDoc for why the empty `{}` fallback masks real
        // signal on the top-of-trace POST endpoints.
        val fixtures = loadBodyFixtures()

        // Persist fixture status RIGHT NOW so the operator can see it without
        // waiting for gradle to flush the test-results XML at end-of-test
        // (which never happens if the JVM hangs on stuck HTTP calls).
        run {
            val reportDir = java.io.File("build/reports/compat").also { it.mkdirs() }
            java.io.File(reportDir, "trace-replay-fixtures.txt").writeText(
                "singleArtifactReady=${fixtures.singleArtifact != null}\n" +
                    "batchArtifactsSize=${fixtures.batchArtifacts.size}\n" +
                    "rmsConfigured=${!fixtures.rmsUrl.isNullOrBlank()}\n" +
                    "rmsUrl=${fixtures.rmsUrl ?: "<unset>"}\n",
            )
        }

        // Vacuous-pass guard: if `loadBodyFixtures` could not pull at least 3 real
        // GAV triples from baseline's /v2/components, every POST against
        // /find-by-artifact{s} falls back to the `("x","x","x")` dummy. Both stands
        // then return a symmetric 404 / empty payload and the trace replay reports
        // 0 diffs *for the wrong reason* — the comparison never reached the lookup
        // path. Skip via `assumeTrue` so the run is reported as SKIPPED (not GREEN)
        // and the operator investigates the baseline /v2/components response.
        // Threshold of 3 mirrors `BodyFixtures.batchArtifacts` target size of 5 —
        // we tolerate a small shortfall but not a wholesale empty-discovery.
        assumeTrue(
            fixtures.singleArtifact != null && fixtures.batchArtifacts.size >= 3,
            "body fixtures unavailable (singleArtifact=${fixtures.singleArtifact != null}, " +
                "batchArtifactsSize=${fixtures.batchArtifacts.size}). trace replay would be " +
                "vacuous for /find-by-artifact{s} — verify baseline /v2/components returns " +
                "components with non-empty distribution.GAV before re-running.",
        )

        // Parallel execution via a dedicated thread pool. CompatibilityTestBase's
        // limiter would also work, but using a separate pool here keeps the trace-
        // replay sized independently from the per-stand HTTP cap (CompatibilityTestBase
        // limits in-flight calls per request, this pool limits total in-flight TUPLES).
        val pool = Executors.newFixedThreadPool(config.parallelism)
        val ts = java.time.Instant.now().toString()
        val processed = AtomicInteger(0)
        val withDiffs = AtomicInteger(0)
        // Per-(endpoint, category) weight accumulator. Endpoint is the templated form
        // (method + path with concrete params, NOT the JsonNode-class path) — same
        // grain `Comparators.compareRaw` uses for DiffRecord.endpoint, so the buckets
        // align with the existing summary.md grouping.
        val weightByBucket = ConcurrentHashMap<Pair<String, DiffClassifier>, Long>()

        // FOLLOW-UP (Stage-2 review flagged): symmetric-failure blind spot. When both
        // stands return identical 4xx/5xx with identical error bodies, Comparators.compareRaw
        // records no STATUS_CODE_DIFF (statuses match) and no STRUCTURAL_DIFF (bodies match) —
        // the tuple silently registers as "no diff". A common-mode regression (both stands
        // wedged on the same downstream) would therefore look clean. Mitigations to consider:
        //   (a) classify (>=400, >=400) pairs above a threshold as a new BOTH_STANDS_ERRORED
        //       category, surfaced in summary.md as an env-warning.
        //   (b) require a minimum 2xx rate per (endpoint, day) — fail the run if it drops.
        // Out of scope for this PR (would also need a known-deltas extension for legitimate
        // pre-existing 4xx like the `/projects/{p}/jira-components` family for unknown
        // projects).

        // Capture which entries hung (HTTP didn't return within the per-future cap).
        // Written to a file at the end so operators can inspect server-side slowness.
        val hungEntries = java.util.concurrent.ConcurrentLinkedQueue<TraceEntry>()
        // entry → future map for per-future timeout + cancel-on-hang.
        val futureToEntry = java.util.concurrent.ConcurrentHashMap<java.util.concurrent.Future<*>, TraceEntry>()

        // Verbose mode: log every request before + after with timing, status, and body sizes.
        // Useful for debug-batch runs (~30 tuples) where we want full visibility into
        // which call hangs / errors. Interleaved across worker threads but includes the
        // thread name so the operator can demultiplex if needed. Keep OFF for 20k runs.
        //
        // SECURITY NOTE: with verbose=on, POST body previews include real group/artifact/
        // version triples discovered from the baseline /v2/components response. Run output
        // is therefore NOT safe to paste verbatim into the public repo or Slack — treat the
        // gradle stdout as operator-confidential.
        val verbose =
            (System.getProperty("compat.verbose") ?: System.getenv("COMPAT_VERBOSE"))
                ?.equals("true", ignoreCase = true) == true

        try {
            val futures =
                entries.map { entry ->
                    pool.submit {
                        // ThreadLocal count — `snapshot().size` is the global queue and
                        // races with other workers between read points. count() is
                        // per-worker-thread, so the delta is "diffs this tuple recorded"
                        // even under fan-out parallelism.
                        val diffsBefore = DiffCollector.count()
                        val t0 = System.nanoTime()
                        if (verbose) log.info("→ ${entry.method} ${entry.path}")
                        val (baseline, candidate) =
                            try {
                                when (entry.method.uppercase()) {
                                    "GET" -> fetchPair(entry.path)
                                    "POST" -> {
                                        val body = chooseBody(entry.path, fixtures)
                                        if (verbose) {
                                            val bodyPreview = body.toString().take(120)
                                            log.info("  body[${body::class.simpleName}]: $bodyPreview")
                                        }
                                        postJsonPair(entry.path, body)
                                    }
                                    "PUT" -> putPair(entry.path)
                                    else -> {
                                        log.debug("skipping unsupported method ${entry.method} ${entry.path}")
                                        return@submit
                                    }
                                }
                            } catch (e: Exception) {
                                log.warn("transport failed for ${entry.method} ${entry.path}: ${e.message}")
                                return@submit
                            }
                        val dtMs = (System.nanoTime() - t0) / 1_000_000
                        val (pathOnly, parsedQuery) = parsePathAndQuery(entry.path)
                        val endpoint = "${entry.method} $pathOnly"
                        val queryParams = parsedQuery + ("_weight" to entry.count.toString())
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
                        val newDiffs = DiffCollector.count() - diffsBefore
                        if (verbose) {
                            log.info(
                                "← ${entry.method} ${entry.path} " +
                                    "b=${baseline.status}/${baseline.bodyBytes.size}B " +
                                    "c=${candidate.status}/${candidate.bodyBytes.size}B " +
                                    "${dtMs}ms diffs=$newDiffs cats=${cats.distinct()}",
                            )
                        }
                        val n = processed.incrementAndGet()
                        if (newDiffs > 0) withDiffs.incrementAndGet()
                        if (n % 100 == 0) {
                            log.info(
                                "trace-replay progress: $n / ${entries.size} " +
                                    "(${withDiffs.get()} tuples with diffs, ${hungEntries.size} hung)",
                            )
                        }
                    }.also { f -> futureToEntry[f] = entry }
                }
            // Per-future hard cap. OkHttp's callTimeout(60s) is theoretically a cap,
            // but in practice some sun.nio.ch.Net.poll calls survived 12+ minutes
            // (observed 2026-05-17). The 90 s budget here is the belt-and-braces
            // ceiling that lets the replay finish even if individual sockets are
            // wedged on the server side.
            //
            // FOLLOW-UP (Stage-2 review flagged): the loop walks futures in submission
            // order, so a slow future at index N delays inspection of N+1..end. The 90 s
            // "budget" is therefore wall-clock from `get`, not from submission. For 20k
            // tuples × parallelism 10 this hasn't mattered (all completed in 13m), but a
            // pathological all-hung run can balloon. Future fix: ExecutorCompletionService
            // with a single deadline per submission, or a separate watchdog thread that
            // walks `(future, submittedAt)` pairs.
            futures.forEach { f ->
                val entry = futureToEntry[f]
                try {
                    f.get(90, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    val ep = "${entry?.method ?: "?"} ${entry?.path ?: "?"}"
                    log.warn("HUNG (>90s, cancelling): $ep")
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

        // Persist hung-entry list for postmortem. The trace-replay author wants
        // to see WHICH endpoint URLs the server stalled on — common-mode
        // server-side slowness shows as repeated path prefixes.
        if (hungEntries.isNotEmpty()) {
            val reportDir = java.io.File("build/reports/compat").also { it.mkdirs() }
            java.io.File(reportDir, "trace-replay-hung.txt").writeText(
                hungEntries.joinToString("\n") { "${it.count}\t${it.method}\t${it.path}" } + "\n",
            )
            log.warn("trace-replay: ${hungEntries.size} entries hung; see build/reports/compat/trace-replay-hung.txt")
        }

        // Top-N frequency-weighted summary printed to stdout (and into the gradle
        // test-results XML). For a richer report, see `CompatibilityReporter` —
        // this in-test print is a stop-gap until that task is extended.
        val topN = weightByBucket.entries.sortedByDescending { it.value }.take(20)
        val totalTuples = entries.size
        val totalWithDiffs = withDiffs.get()
        log.info(
            "trace-replay done: $totalTuples tuples replayed, " +
                "$totalWithDiffs produced at least one diff, " +
                "${weightByBucket.size} unique (endpoint, category) buckets",
        )
        if (topN.isNotEmpty()) {
            log.info("=== top-20 frequency-weighted buckets ===")
            topN.forEach { (key, weight) ->
                val (ep, cat) = key
                log.info("  weight=$weight  category=$cat  $ep")
            }
        }

        // Persistent summary artefact — gradle's `showStandardStreams=false` swallows
        // the `log.info` lines above. Write a machine-readable JSON next to the
        // ndjson so external analysis (and TC artifact publishing) gets the totals.
        writeSummary(
            totalTuples = totalTuples,
            totalWithDiffs = totalWithDiffs,
            droppedDiagnostic = droppedDiagnostic,
            fixtures = fixtures,
            topN = topN,
            tsIso = ts,
        )
    }

    /**
     * Write a JSON summary to `build/reports/compat/trace-replay-summary.json`.
     * Captures totals, body-fixture availability, and the top-N frequency-weighted
     * buckets. Designed for TC artifact publishing + post-run analysis (`jq` /
     * Python). Confidentiality: the top-N entries include endpoint paths which
     * may contain real component IDs (`/components/<id>/...`). The build/reports
     * directory is in `.gitignore`, so the file is not at risk of accidental
     * commit — but operators must not paste the file content into commit
     * messages or public docs (`feedback_redacted_identifiers`).
     */
    private fun writeSummary(
        totalTuples: Int,
        totalWithDiffs: Int,
        droppedDiagnostic: Int,
        fixtures: BodyFixtures,
        topN: List<Map.Entry<Pair<String, DiffClassifier>, Long>>,
        tsIso: String,
    ) {
        val reportDir = java.io.File("build/reports/compat")
        reportDir.mkdirs()
        val summaryFile = java.io.File(reportDir, "trace-replay-summary.json")
        val mapper = jacksonObjectMapper()
        val payload =
            mapOf(
                "generatedAt" to tsIso,
                "totals" to mapOf(
                    "tuplesReplayed" to totalTuples,
                    "tuplesWithDiffs" to totalWithDiffs,
                    "tuplesWithoutDiffs" to (totalTuples - totalWithDiffs),
                    "droppedDiagnostic" to droppedDiagnostic,
                ),
                "bodyFixtures" to mapOf(
                    "singleArtifactReady" to (fixtures.singleArtifact != null),
                    "batchArtifactsSize" to fixtures.batchArtifacts.size,
                    "versionsCacheSize" to fixtures.versionsCache.size,
                    "rmsConfigured" to !fixtures.rmsUrl.isNullOrBlank(),
                ),
                "topWeightedBuckets" to topN.map { (key, weight) ->
                    val (ep, cat) = key
                    mapOf("endpoint" to ep, "category" to cat.name, "weight" to weight)
                },
            )
        summaryFile.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
        log.info("trace-replay summary written to ${summaryFile.absolutePath}")
    }

    /** Parse a trace line `<count>\t<METHOD>\t<path>`. Returns null on malformed input. */
    private fun parse(line: String): TraceEntry? {
        val parts = line.split('\t')
        if (parts.size != 3) return null
        val count = parts[0].toLongOrNull() ?: return null
        val method = parts[1].trim().uppercase()
        val path = parts[2].trim()
        if (path.isEmpty() || !path.startsWith('/')) return null
        return TraceEntry(count, method, path)
    }

    companion object {
        /**
         * Split a request path of the form `"/foo/bar?k=v&k2=v2"` into:
         *  - `pathOnly`  — everything before the FIRST `?` (or the whole string if no `?`),
         *  - `queryMap`  — URL-decoded `key -> value` map parsed from the suffix.
         *
         * Splits on the FIRST `?` only (encoded `%3F` in path components stays put).
         * Empty query (`/foo?`) yields an empty map. Key-only entries (`/foo?flag`)
         * map to an empty string value. Repeated keys keep the LAST occurrence — the
         * trace data contains no duplicates in practice, and a `lastWins` policy
         * matches how Spring's controllers typically resolve `@RequestParam`.
         */
        internal fun parsePathAndQuery(rawPath: String): Pair<String, Map<String, String>> {
            val qIdx = rawPath.indexOf('?')
            if (qIdx < 0) return rawPath to emptyMap()
            val pathOnly = rawPath.substring(0, qIdx)
            val rawQuery = rawPath.substring(qIdx + 1)
            if (rawQuery.isEmpty()) return pathOnly to emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val (k, v) =
                    if (eq < 0) {
                        pair to ""
                    } else {
                        pair.substring(0, eq) to pair.substring(eq + 1)
                    }
                val decodedK = runCatching {
                    URLDecoder.decode(k, StandardCharsets.UTF_8)
                }.getOrDefault(k)
                val decodedV = runCatching {
                    URLDecoder.decode(v, StandardCharsets.UTF_8)
                }.getOrDefault(v)
                map[decodedK] = decodedV
            }
            return pathOnly to map
        }
    }
}
