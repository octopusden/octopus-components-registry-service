package org.octopusden.octopus.components.registry.compat

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Unit tests for [resolveReportDir] — the pure helper that picks the per-worker
 * ndjson directory for [ExecutionLogger] and [DiffCollector].
 *
 * Pre-fix history (id17 build #3620, May 2026): `compat.report-dir` was set in
 * build.gradle via `"${projectDir}/build/reports/compat".toString()`, but
 * Gradle on the TC agent rendered `projectDir.toString()` as the relative
 * module basename (`components-registry-compat-test`) rather than the absolute
 * path. The test JVM then received a relative `compat.report-dir`. Combined
 * with `workingDir = projectDir` (which set `user.dir` to the same relative
 * basename), `Path.toAbsolutePath()` resolved the relative value against the
 * relative `user.dir`, landing per-worker ndjson files in a doubled path
 * like `components-registry-compat-test/components-registry-compat-test/
 * build/reports/compat/`. `compatibilityReporter` aggregated from the SHALLOW
 * `<projectDir>/build/reports/compat/` and saw zero entries — the build
 * failed the proof-of-execution guard despite 15834 testcases passing.
 *
 * The fix has two layers:
 *   1. `build.gradle` resolves the writer property at execution time inside a
 *      `doFirst` block and passes it via `jvmArgs "-Dcompat.report-dir=…"`,
 *      using `layout.buildDirectory.dir('reports/compat').get().asFile
 *      .canonicalPath` (defence-in-depth vs `.absolutePath` — canonical
 *      resolves through the filesystem rather than via `user.dir`). The same
 *      Gradle layout primitive backs the `doFirst` cleanup and the
 *      `compatibilityReporter` task, so all three move together if `buildDir`
 *      is ever customised.
 *   2. `resolveReportDir` fails fast when its input is non-null and relative,
 *      so any future regression of (1) surfaces as a clear test-JVM startup
 *      error instead of a silent doubled path that only the operator's grep
 *      of TC artifacts can catch.
 *
 * These tests pin layer (2). Layer (1) is exercised via the next id17
 * compat-test run.
 */
@Tag("unit")
class ReportDirResolutionTest {
    @Test
    fun `null prop returns absolute path of fallback`(@TempDir tmp: Path) {
        val fallback = tmp.resolve("build/reports/compat")
        val out = resolveReportDir(null, fallback)
        assertThat(out).isAbsolute()
        assertThat(out).isEqualTo(fallback.toAbsolutePath())
    }

    @Test
    fun `absolute prop is returned as-is`(@TempDir tmp: Path) {
        val absolute = tmp.resolve("custom/reports").toAbsolutePath()
        val out = resolveReportDir(absolute.toString(), Path.of("ignored-fallback"))
        assertThat(out).isEqualTo(absolute)
    }

    @Test
    fun `relative prop throws IllegalStateException — fail-fast on misconfigured build gradle`() {
        assertThatThrownBy {
            resolveReportDir("components-registry-compat-test/build/reports/compat", Path.of("ignored-fallback"))
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("compat.report-dir must be absolute")
            .hasMessageContaining("components-registry-compat-test/build/reports/compat")
    }

    @Test
    fun `relative prop with single segment also throws`() {
        assertThatThrownBy {
            resolveReportDir("build/reports/compat", Path.of("ignored-fallback"))
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("compat.report-dir must be absolute")
    }
}
