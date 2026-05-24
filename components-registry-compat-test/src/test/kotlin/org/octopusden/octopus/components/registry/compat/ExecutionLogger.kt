package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/**
 * Per-case execution log: every endpoint × component × params triple, regardless of diff outcome.
 *
 * Independent from [DiffCollector] — even clean runs produce execution-log.ndjson so the operator
 * can confirm what was actually exercised.
 */
object ExecutionLogger {
    private val mapper = jacksonObjectMapper()
    private val writeLock = Any()
    private var counter = 0
    private var diffCounter = 0
    // Per-worker JVM counter; combine across workers in `compatibilityReporter` for
    // a cross-fork total. The progress line below is best-effort visibility for the
    // TC log, not a hard count for the operator (use execution-log.md for that).
    // Print every 50 entries — at parallelism=8 that's roughly every ~5 sec of
    // wall-clock for typical endpoint latencies, fast enough to feel live in TC
    // without flooding the log on a 10k-request full sweep.
    private const val PROGRESS_EVERY = 50

    private val workerFile: Path by lazy {
        // Delegated to `resolveReportDir` (shared with DiffCollector). The
        // resolver fails fast if `compat.report-dir` is non-null and
        // relative — observed in id17 build #3620 that a relative value
        // resolved against a relative `user.dir` writes to a doubled-prefix
        // path the compatibilityReporter never reads, producing a vacuously-
        // clean build despite 15834 testcases passing.
        val baseDirProp = System.getProperty("compat.report-dir")
        val reportDir = resolveReportDir(baseDirProp, Path.of("build/reports/compat"))
            .also { Files.createDirectories(it) }
        reportDir.resolve("exec-worker-${ProcessHandle.current().pid()}-${UUID.randomUUID()}.ndjson")
    }
    private val writer: BufferedWriter by lazy {
        // Diagnostic print — fired once per worker JVM on first write. Builds
        // #3620 and #3630 both showed empty per-worker ndjson in the
        // aggregated artifact despite the worker counters reporting 15834
        // requests; this trace captures every path representation we can get
        // our hands on so the next run can be diffed against the
        // compatibilityReporter's view (see build.gradle `compatibilityReporter`
        // task — it prints the matching trace from its end).
        val propValue = System.getProperty("compat.report-dir") ?: "(unset)"
        val cwd = System.getProperty("user.dir") ?: "(unknown)"
        val workerFileObj = workerFile.toFile()
        val workerAbs = workerFile.toAbsolutePath().toString()
        val workerCanon = runCatching { workerFileObj.canonicalPath }.getOrElse { "(canon failed: ${it.message})" }
        val parentExists = workerFile.parent?.let { Files.exists(it) } ?: false
        val parentDir = workerFile.parent?.toString() ?: "(no parent)"
        val parentDirCanon = workerFile.parent?.let { runCatching { it.toFile().canonicalPath }.getOrElse { e -> "(canon failed: ${e.message})" } } ?: "(no parent)"
        val osCwdCanon = runCatching { java.io.File(".").canonicalPath }.getOrElse { "(canon failed: ${it.message})" }
        System.out.println("[compat-exec] === ExecutionLogger init diagnostic ===")
        System.out.println("[compat-exec]   compat.report-dir (raw)   = $propValue")
        System.out.println("[compat-exec]   user.dir (sysprop)        = $cwd")
        System.out.println("[compat-exec]   OS-level CWD (canonical)  = $osCwdCanon")
        System.out.println("[compat-exec]   workerFile.toAbsolutePath = $workerAbs")
        System.out.println("[compat-exec]   workerFile.canonicalPath  = $workerCanon")
        System.out.println("[compat-exec]   parent dir                = $parentDir")
        System.out.println("[compat-exec]   parent dir canonical      = $parentDirCanon")
        System.out.println("[compat-exec]   parent dir exists         = $parentExists")
        System.out.println("[compat-exec] === /diagnostic ===")
        System.out.flush()
        val w = Files.newBufferedWriter(
            workerFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        Runtime.getRuntime().addShutdownHook(Thread {
            synchronized(writeLock) {
                runCatching { w.flush() }
                runCatching { w.close() }
                System.out.println("[compat-exec] worker pid=${ProcessHandle.current().pid()} totals: $counter requests ($diffCounter with diffs)")
            }
        })
        w
    }

    fun log(entry: ExecutionEntry) {
        synchronized(writeLock) {
            writer.write(mapper.writeValueAsString(entry))
            writer.newLine()
            writer.flush()
            counter++
            if (entry.diffCount > 0) diffCounter++
            if (counter == 1 || counter % PROGRESS_EVERY == 0) {
                // Brief one-line progress so TC's build log shows the run is doing
                // real work. Last endpoint + status pair is enough to debug
                // "stuck at N" cases (e.g. baseline timing out on a single path).
                System.out.println(
                    "[compat-exec] $counter requests ($diffCounter with diffs) — " +
                        "last: ${entry.endpoint} b=${entry.baselineStatus} c=${entry.candidateStatus} " +
                        "b=${entry.baselineMs}ms c=${entry.candidateMs}ms diffs=${entry.diffCount}",
                )
                System.out.flush()
            }
        }
    }

    /** Flushes only — real close happens via JVM shutdown hook (see lazy init). */
    fun close() {
        synchronized(writeLock) {
            runCatching { writer.flush() }
        }
    }
}

data class ExecutionEntry(
    val ts: String = Instant.now().toString(),
    val worker: String = ProcessHandle.current().pid().toString(),
    val endpoint: String,
    val pathParams: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val baselineStatus: Int,
    val candidateStatus: Int,
    val baselineMs: Long,
    val candidateMs: Long,
    val layer: String,
    val diffCount: Int,
)
