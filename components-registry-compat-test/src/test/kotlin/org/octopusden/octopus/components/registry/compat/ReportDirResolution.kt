package org.octopusden.octopus.components.registry.compat

import java.nio.file.Path

/**
 * Resolve the per-worker ndjson directory shared between [ExecutionLogger] and
 * [DiffCollector]. Centralised so a future change to the resolution rule lands
 * in one place and both writers stay in sync with the dir
 * `compatibilityReporter` aggregates from.
 *
 * Inputs:
 *  - [reportDirProp] — the raw `compat.report-dir` system-property value from
 *    the test JVM. `build.gradle` is expected to set this to the absolute path
 *    of `<projectDir>/build/reports/compat`. `null` means the property was
 *    never set (direct CLI invocation, no gradle context).
 *  - [fallback] — used only when [reportDirProp] is `null`. Callers pass a
 *    sensible relative or absolute default; we normalise to absolute before
 *    returning.
 *
 * Failure mode (fail-fast):
 *  - If [reportDirProp] is non-null and resolves to a relative `Path`, throw
 *    [IllegalStateException] immediately. This catches the regression that
 *    masked id17 build #3620: the test JVM accepted a relative
 *    `compat.report-dir`, resolved it against a relative `user.dir`, and
 *    wrote per-worker files to a doubled-prefix path that
 *    `compatibilityReporter` never inspected. The build then failed the
 *    proof-of-execution guard with no clear pointer to the actual
 *    misconfiguration. Failing fast here means the next misconfigured build
 *    surfaces with a precise error in the first test JVM log line.
 */
internal fun resolveReportDir(
    reportDirProp: String?,
    fallback: Path,
): Path {
    if (reportDirProp != null) {
        val candidate = Path.of(reportDirProp)
        check(candidate.isAbsolute) {
            "compat.report-dir must be absolute — got '$reportDirProp'. " +
                "build.gradle should pass an absolute path (e.g. via " +
                "`file('build/reports/compat').absolutePath`); a relative " +
                "value resolved against the test JVM's user.dir can land " +
                "per-worker ndjson files where compatibilityReporter never " +
                "reads them, producing a vacuously-clean build."
        }
        return candidate
    }
    return fallback.toAbsolutePath()
}
