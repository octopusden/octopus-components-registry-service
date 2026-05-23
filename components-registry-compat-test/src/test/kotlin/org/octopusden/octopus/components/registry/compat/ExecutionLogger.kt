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
        val reportDir = Path.of("build/reports/compat").also { Files.createDirectories(it) }
        reportDir.resolve("exec-worker-${ProcessHandle.current().pid()}-${UUID.randomUUID()}.ndjson")
    }
    private val writer: BufferedWriter by lazy {
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
