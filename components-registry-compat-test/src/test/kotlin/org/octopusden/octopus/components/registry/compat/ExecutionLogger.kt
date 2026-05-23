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
        // System property `compat.report-dir` is set by the gradle test task to
        // an ABSOLUTE path (layout.buildDirectory/reports/compat). Falling back
        // to a relative `build/reports/compat` only protects against direct CLI
        // invocations of the test class — under gradle that fallback would
        // resolve to whatever JVM CWD gradle picked for the fork (sometimes
        // the agent's $HOME, not the module dir), and the compatibilityReporter
        // task would never find the exec-worker file. Symptom observed in id17
        // build #9: 15834 progress lines on stdout but reporter saw 0 files.
        val baseDirProp = System.getProperty("compat.report-dir")
        val reportDir = (if (baseDirProp != null) Path.of(baseDirProp) else Path.of("build/reports/compat"))
            .toAbsolutePath()
            .also { Files.createDirectories(it) }
        reportDir.resolve("exec-worker-${ProcessHandle.current().pid()}-${UUID.randomUUID()}.ndjson")
    }
    private val writer: BufferedWriter by lazy {
        // Log the resolved absolute path once on first write — gives the
        // operator a single grep target ("Compat exec-log path:") in the TC
        // build log if the path-vs-reporter mismatch ever recurs.
        System.out.println("[compat-exec] Compat exec-log path: ${workerFile.toAbsolutePath()}")
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
