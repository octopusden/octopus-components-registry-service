package org.octopusden.octopus.components.registry.compat

/**
 * Run-time configuration sourced from -P / env. See README.md.
 */
data class CompatConfig(
    val baselineUrl: String?,
    val candidateUrl: String?,
    val rmsUrl: String?,
    val full: Boolean,
    val parallelism: Int,
    val malformed: Boolean,
    val versionsFallback: Boolean,
    /** Hard cap on the number of components iterated per test class. Null = no cap. */
    val maxComponents: Int?,
    /**
     * Absolute path to a `{componentId: [v1, v2, v3]}` JSON snapshot produced by
     * `crs-compat-trace/scripts/dump-versions.py`. When set, [VersionSampler]
     * reads from this file instead of querying [rmsUrl] on every call — TC runs
     * stay reproducible across days and don't burn RMS round-trips. `null`
     * (default) keeps the RMS fallback for local dev.
     */
    val versionsFile: String?,
) {
    /** Either both URLs are set (active run), or neither (skipped). */
    val active: Boolean get() = !baselineUrl.isNullOrBlank() && !candidateUrl.isNullOrBlank()

    /**
     * Configuration is invalid (not just inactive) when exactly one URL is set.
     * Tests must fail-fast in this case rather than silently skip.
     */
    val partialUrls: Boolean get() =
        baselineUrl.isNullOrBlank() xor candidateUrl.isNullOrBlank()

    fun explainInvalid(): String? = when {
        !partialUrls -> null
        baselineUrl.isNullOrBlank() ->
            "compat.candidate.url is set but compat.baseline.url is missing — both required, or neither"
        else ->
            "compat.baseline.url is set but compat.candidate.url is missing — both required, or neither"
    }

    companion object {
        /** Smoke default — enough to validate the pipeline, fast enough for debugging. */
        const val SMOKE_MAX_COMPONENTS = 10

        fun load(): CompatConfig {
            val full = read("compat.full")?.toBooleanStrictOrNull() ?: false
            val maxComponentsRaw = read("compat.max-components")?.toIntOrNull()
            // Explicit override wins. Otherwise: smoke caps at 10 (debugging-friendly),
            // full leaves it open (null = no cap).
            val maxComponents = maxComponentsRaw ?: if (full) null else SMOKE_MAX_COMPONENTS
            return CompatConfig(
                baselineUrl = read("compat.baseline.url"),
                candidateUrl = read("compat.candidate.url"),
                rmsUrl = read("compat.rms.url"),
                full = full,
                parallelism = read("compat.parallelism")?.toIntOrNull() ?: 8,
                malformed = read("compat.malformed")?.toBooleanStrictOrNull() ?: false,
                versionsFallback = read("compat.versions-fallback")?.toBooleanStrictOrNull() ?: false,
                maxComponents = maxComponents,
                versionsFile = read("compat.versions.file"),
            )
        }

        private fun read(key: String): String? {
            System.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }
            val envKey = key.replace('.', '_').replace('-', '_').uppercase()
            return System.getenv(envKey)?.takeIf { it.isNotBlank() }
        }
    }
}
