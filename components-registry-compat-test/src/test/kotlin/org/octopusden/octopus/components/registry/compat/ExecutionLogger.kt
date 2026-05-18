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
            }
        })
        w
    }

    fun log(entry: ExecutionEntry) {
        synchronized(writeLock) {
            writer.write(mapper.writeValueAsString(entry))
            writer.newLine()
            writer.flush()
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
