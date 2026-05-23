package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Pure loader for the versions JSON. Extracted from [VersionSampler] so unit
 * tests can exercise it without the by-lazy singleton state of the object.
 *
 * Contract:
 * - `path = null` → returns `null` (caller should fall back to RMS).
 * - path doesn't exist / not readable → returns empty map (operator opted into
 *   file mode and put the wrong path; the loader does NOT silently fall back
 *   to RMS in this case — that would defeat the reproducibility-vs-RMS
 *   priority the file mode promises).
 * - File present but not a JSON object (e.g. `[]`, `"foo"`) → empty map.
 * - File present, valid JSON object → `{componentId: [v1, ...]}`. Entries
 *   whose value is not a JSON array are skipped. Array elements that are
 *   not non-blank strings are filtered out.
 *
 * KNOWN GAP: the "operator opted into file mode and put the wrong path → 0
 * real versions for every component" outcome does NOT fire any enforcement
 * check today. The plan §RMS coverage guard speaks of a smoke-floor
 * (`each component must have ≥1 real version`), but no such gate is
 * implemented in any consumer of [versionsFor] — they all `log.warn` +
 * skip per-component tests via `assumeTrue` / `pairArgsOrSentinel`. A
 * vacuous-green run is therefore possible if the file is empty / wrong-
 * path. Follow-up: lift the floor into `SnapshotPreconditionTest` so a
 * misconfigured file fails fast instead of silently skipping.
 */
internal fun loadVersionsFile(
    path: String?,
    mapper: com.fasterxml.jackson.databind.ObjectMapper,
    warn: (String) -> Unit = {},
    info: (String) -> Unit = {},
): Map<String, List<String>>? {
    if (path == null) return null
    val f = File(path)
    if (!f.isFile || !f.canRead()) {
        warn("compat.versions.file=$path is not a readable file; version-file path is empty for ALL components")
        return emptyMap()
    }
    return runCatching {
        val node = mapper.readTree(f)
        if (!node.isObject) {
            warn("compat.versions.file=$path is not a JSON object; treating as empty")
            return@runCatching emptyMap<String, List<String>>()
        }
        buildMap<String, List<String>> {
            node.fields().forEach { (k, v) ->
                if (v.isArray) {
                    put(k, v.mapNotNull { it.takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank) })
                }
            }
        }.also { info("compat.versions.file: loaded ${it.size} components from $path") }
    }.onFailure {
        warn("compat.versions.file=$path failed to parse: ${it.message}; treating as empty")
    }.getOrDefault(emptyMap())
}

/**
 * Per-component version discovery — two sources, priority in this order:
 *
 *   1. A pre-computed `{componentId: [v1, v2, ...]}` JSON snapshot when
 *      `compat.versions.file` is configured (set by TC builds via the
 *      `crs-compat-trace` VCS root). Read once, cached for the run. No HTTP
 *      hits — repeatable across days and isolated from RMS availability.
 *   2. Live `GET {rms.url}/rest/api/1/builds/component/{c}?statuses=RELEASE&descending=true&limit=N`
 *      when no file is configured (kept for local dev so an operator with an
 *      RMS URL can still drive the sampler).
 *
 * Per-component cache (`ConcurrentHashMap`) is shared between the two paths.
 *
 * Lifecycle / scoping:
 *   - The file-mode map is read ONCE at first [versionsFor] call via
 *     `by lazy`. `compat.versions.file` MUST be set before that first call;
 *     subsequent property changes are silently ignored. Always true in
 *     production (TC sets the env before the JVM starts), but a unit test
 *     that flips the property between cases will not observe the new value.
 *   - The version cache (`ConcurrentHashMap`) is per-JVM. If gradle reuses
 *     a worker JVM across `:test` and `:unitTest` invocations with
 *     different `compat.versions.file` values, the second invocation sees
 *     the first's cached map. Pre-existing behaviour for the RMS path;
 *     unchanged here.
 *
 * Fail mode: if neither source has a component, [versionsFor] returns
 * `emptyList()` — see [loadVersionsFile] KDoc for the documented "vacuous-
 * green path on wrong file-mode config" gap and follow-up.
 */
object VersionSampler {
    private val log = LoggerFactory.getLogger(VersionSampler::class.java)
    private val mapper = jacksonObjectMapper()
    private val cache = ConcurrentHashMap<String, List<String>>()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Lazily-loaded `{componentId: [version, ...]}` map, populated on first
     * lookup when `compat.versions.file` is configured. `null` once-and-for-all
     * when no file is configured. The lazy initialiser also handles file-not-
     * found / unparseable JSON by logging a warning and returning an empty map
     * (the RMS path is not used as a fallback in that case — the operator
     * explicitly asked for the file mode, so a missing file is operator
     * error, not a reason to silently fall back).
     */
    private val fileMap: Map<String, List<String>>? by lazy {
        // Wrap log methods in lambdas because slf4j's Logger has overloads
        // that make `log::warn` / `log::info` references ambiguous.
        loadVersionsFile(
            CompatConfig.load().versionsFile,
            mapper,
            warn = { msg -> log.warn(msg) },
            info = { msg -> log.info(msg) },
        )
    }

    /**
     * Fetch up to [limit] real release versions for [componentName]. Cached per call regardless
     * of [limit] (first caller wins). Returns empty list when neither source has the component.
     */
    fun versionsFor(componentName: String, limit: Int = 5, rmsUrl: String? = CompatConfig.load().rmsUrl): List<String> {
        return cache.computeIfAbsent(componentName) {
            // File path wins when configured: operator explicitly opted into
            // the snapshot, so we don't sneak past it to RMS even if RMS is
            // available (would defeat reproducibility).
            fileMap?.let { return@computeIfAbsent (it[componentName] ?: emptyList()).take(limit) }
            if (rmsUrl.isNullOrBlank()) return@computeIfAbsent emptyList()
            fetchFromRms(componentName, limit, rmsUrl)
        }
    }

    private fun fetchFromRms(name: String, limit: Int, rmsUrl: String): List<String> {
        val base = rmsUrl.trimEnd('/')
        val url = "$base/rest/api/1/builds/component/$name?statuses=RELEASE&descending=true&limit=$limit"
        return runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.code !in 200..299) {
                    log.warn("RMS returned {} for component={}; treating as no releases", resp.code, name)
                    return@use emptyList<String>()
                }
                val body = resp.body?.string().orEmpty()
                val tree: JsonNode = mapper.readTree(body)
                if (!tree.isArray) {
                    log.warn("RMS body for {} not an array: {}", name, body.take(200))
                    return@use emptyList<String>()
                }
                tree.mapNotNull { it.path("version").takeIf { v -> v.isTextual }?.asText() }
                    .filter { it.isNotBlank() }
                    .distinct()
            }
        }.onFailure {
            log.warn("RMS fetch failed for component={}: {}", name, it.message)
        }.getOrDefault(emptyList())
    }
}
