package org.octopusden.octopus.components.registry.compat

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.VersionRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Targeted replay of **only** requests that produced diffs on a prior TC run.
 *
 * Activate with `compat.residual.file` / `COMPAT_RESIDUAL_FILE` pointing at a
 * trace-format fixture (`count\\tMETHOD\\tpath` per line). Generate the fixture
 * from TC `exec-worker-*.ndjson` via
 * [`scripts/local-stands/extract-residual-fixture.py`](../../../../../../scripts/local-stands/extract-residual-fixture.py).
 *
 * Uses the same [Comparators.compareRaw] pipeline as [TraceReplayCompatTest] but
 * skips the 30k production trace — fast iteration on the ~700 failing tuples from
 * build #3841 instead of re-running the full [1.7] gate.
 */
class ResidualReplayCompatTest : CompatibilityTestBase() {
    private data class ResidualEntry(val count: Long, val method: String, val path: String)

    @Test
    fun `replay residual failing requests from prior compat run`() {
        val filePath =
            System.getProperty("compat.residual.file")
                ?: System.getenv("COMPAT_RESIDUAL_FILE")
        assumeTrue(
            !filePath.isNullOrBlank(),
            "compat.residual.file / COMPAT_RESIDUAL_FILE not set — ResidualReplayCompatTest skipped",
        )
        val file = File(filePath!!)
        assumeTrue(file.exists() && file.canRead(), "residual fixture not readable: $filePath")

        val entries =
            file
                .readLines()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val parts = trimmed.split('\t')
                    if (parts.size < 3) return@mapNotNull null
                    ResidualEntry(parts[0].toLongOrNull() ?: 1L, parts[1], parts[2])
                }
        assumeTrue(entries.isNotEmpty(), "residual fixture empty: $filePath")

        val pool = Executors.newFixedThreadPool(config.parallelism.coerceAtMost(4))
        val processed = AtomicInteger(0)
        val withDiffs = AtomicInteger(0)

        try {
            val futures =
                entries.map { entry ->
                    pool.submit {
                        val diffsBefore = DiffCollector.count()
                        val (baseline, candidate) =
                            when (entry.method.uppercase()) {
                                "GET" -> fetchPair(entry.path)
                                "PUT" -> putPair(entry.path)
                                "POST" ->
                                    if (entry.path.contains("/detailed-versions")) {
                                        val component =
                                            entry.path
                                                .substringAfter("/components/")
                                                .substringBefore("/detailed-versions")
                                        val versions = VersionSampler.versionsFor(component, 3)
                                        postJsonPair(entry.path, VersionRequest(versions))
                                    } else {
                                        postJsonPair(entry.path, emptyMap<String, Any>())
                                    }
                                else -> return@submit
                            }
                        val (pathOnly, parsedQuery) = parsePathAndQuery(entry.path)
                        val endpoint = "${entry.method.uppercase()} $pathOnly"
                        Comparators.compareRaw(
                            endpoint = endpoint,
                            pathParams = emptyMap(),
                            baseline = baseline,
                            candidate = candidate,
                            queryParams = parsedQuery + ("_residual" to "1"),
                        )
                        ExecutionLogger.log(
                            ExecutionEntry(
                                endpoint = endpoint,
                                pathParams = emptyMap(),
                                queryParams = parsedQuery,
                                baselineStatus = baseline.status,
                                candidateStatus = candidate.status,
                                baselineMs = baseline.durationMs,
                                candidateMs = candidate.durationMs,
                                layer = "raw",
                                diffCount = DiffCollector.count() - diffsBefore,
                            ),
                        )
                        val newDiffs = DiffCollector.count() - diffsBefore
                        if (newDiffs > 0) withDiffs.incrementAndGet()
                        val n = processed.incrementAndGet()
                        if (n % 50 == 0 || n == entries.size) {
                            println(
                                "[residual-replay] $n / ${entries.size} " +
                                    "(${withDiffs.get()} tuples with diffs)",
                            )
                        }
                    }
                }
            futures.forEach { it.get(90, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
        println(
            "[residual-replay] done: ${entries.size} tuples, " +
                "${withDiffs.get()} with diffs, " +
                "${DiffCollector.count()} diff records total",
        )
    }

    private fun parsePathAndQuery(rawPath: String): Pair<String, Map<String, String>> {
        val qIdx = rawPath.indexOf('?')
        if (qIdx < 0) return rawPath to emptyMap()
        val pathOnly = rawPath.substring(0, qIdx)
        val query =
            rawPath
                .substring(qIdx + 1)
                .split('&')
                .mapNotNull { pair ->
                    if (pair.isEmpty()) return@mapNotNull null
                    val eq = pair.indexOf('=')
                    if (eq <= 0) pair to "" else pair.substring(0, eq) to pair.substring(eq + 1)
                }.toMap()
        return pathOnly to query
    }
}
