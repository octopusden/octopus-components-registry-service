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
        // Delegated to the shared `resolveReportDir` helper (same one
        // ExecutionLogger uses). Fails fast if `compat.report-dir` is
        // non-null and relative — observed in id17 build #3620 that a
        // relative value resolved against a relative `user.dir` writes to
        // a doubled-prefix path the compatibilityReporter never reads.
        val baseDirProp = System.getProperty("compat.report-dir")
        val reportDir = resolveReportDir(baseDirProp, Path.of("build/reports/compat"))
            .also { Files.createDirectories(it) }
        reportDir.resolve("diff-worker-${ProcessHandle.current().pid()}-${UUID.randomUUID()}.ndjson")
    }
    private val writer: BufferedWriter by lazy {
        // Diagnostic mirror of ExecutionLogger — fires once per worker JVM
        // on the first diff record. Lets the operator compare the path
        // DiffCollector wrote to against the path compatibilityReporter
        // read from in the same TC log.
        val propValue = System.getProperty("compat.report-dir") ?: "(unset)"
        val cwd = System.getProperty("user.dir") ?: "(unknown)"
        val workerFileObj = workerFile.toFile()
        val workerAbs = workerFile.toAbsolutePath().toString()
        val workerCanon = runCatching { workerFileObj.canonicalPath }.getOrElse { "(canon failed: ${it.message})" }
        val parentExists = workerFile.parent?.let { Files.exists(it) } ?: false
        val parentDir = workerFile.parent?.toString() ?: "(no parent)"
        val parentDirCanon = workerFile.parent?.let { runCatching { it.toFile().canonicalPath }.getOrElse { e -> "(canon failed: ${e.message})" } } ?: "(no parent)"
        val osCwdCanon = runCatching { java.io.File(".").canonicalPath }.getOrElse { "(canon failed: ${it.message})" }
        System.out.println("[compat-diff] === DiffCollector init diagnostic ===")
        System.out.println("[compat-diff]   compat.report-dir (raw)   = $propValue")
        System.out.println("[compat-diff]   user.dir (sysprop)        = $cwd")
        System.out.println("[compat-diff]   OS-level CWD (canonical)  = $osCwdCanon")
        System.out.println("[compat-diff]   workerFile.toAbsolutePath = $workerAbs")
        System.out.println("[compat-diff]   workerFile.canonicalPath  = $workerCanon")
        System.out.println("[compat-diff]   parent dir                = $parentDir")
        System.out.println("[compat-diff]   parent dir canonical      = $parentDirCanon")
        System.out.println("[compat-diff]   parent dir exists         = $parentExists")
        System.out.println("[compat-diff] === /diagnostic ===")
        System.out.flush()
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
                System.out.println("[compat-diff] worker pid=${ProcessHandle.current().pid()} totals: ${records.size} diff records persisted to $workerAbs")
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
