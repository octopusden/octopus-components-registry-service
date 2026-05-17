package org.octopusden.octopus.components.registry.compat

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe accumulator for [DiffRecord]s.
 *
 * Each Gradle test worker writes to its own ndjson file (one record = one line)
 * under build/reports/compat/. CompatibilityReporter (Gradle finalizedBy task)
 * merges all worker files into summary.md after the test run.
 *
 * Records are also kept in memory for @AfterAll classification + fail decision.
 */
object DiffCollector {
    private val records = ConcurrentLinkedQueue<DiffRecord>()
    private val writeLock = Any()
    // Per-thread count of records this thread has emitted. JUnit 5 concurrent
    // execution runs one test method on one thread for its lifetime, so the
    // `count()` delta captured around a single test method's body reports only
    // that method's diffs — not those produced concurrently by other test methods
    // sharing the process-wide `records` queue.
    private val threadLocalCount = ThreadLocal.withInitial { 0 }
    private val workerFile: Path by lazy {
        val reportDir = Path.of("build/reports/compat").also { Files.createDirectories(it) }
        reportDir.resolve("diff-worker-${ProcessHandle.current().pid()}-${UUID.randomUUID()}.ndjson")
    }
    private val writer: BufferedWriter by lazy {
        val w = Files.newBufferedWriter(
            workerFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        // The singleton writer must outlive every per-test-class @AfterAll. Per-class
        // close() would race other parallel test classes still recording diffs and crash
        // them with "Stream closed". Close exactly once at JVM shutdown.
        Runtime.getRuntime().addShutdownHook(Thread {
            synchronized(writeLock) {
                runCatching { w.flush() }
                runCatching { w.close() }
            }
        })
        w
    }

    fun record(diff: DiffRecord) {
        records.add(diff)
        threadLocalCount.set(threadLocalCount.get() + 1)
        synchronized(writeLock) {
            writer.write(diff.toJsonLine())
            writer.newLine()
            writer.flush()
        }
    }

    fun snapshot(): List<DiffRecord> = records.toList()

    /**
     * Number of records emitted by the **current thread**. Used by test methods
     * to capture before/after deltas without picking up diffs concurrently
     * recorded by parallel test methods. Use [snapshot]`.size` if you need the
     * process-wide total.
     */
    fun count(): Int = threadLocalCount.get()

    fun clear() {
        records.clear()
        threadLocalCount.remove()
    }

    /**
     * Flushes the buffered writer; does NOT close it. Each test class's `@AfterAll`
     * may invoke this, but real close happens once via the shutdown hook (see lazy init
     * above). Keeping the writer open for the whole JVM avoids "Stream closed" races
     * with other parallel test classes.
     */
    fun close() {
        synchronized(writeLock) {
            runCatching { writer.flush() }
        }
    }
}
