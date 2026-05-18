package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Per-component version discovery via Release Management Service.
 *
 * Hits `GET {rms.url}/rest/api/1/builds/component/{c}?statuses=RELEASE&descending=true&limit=N`
 * and returns up to N release versions, newest first. Cached per component for the whole test run.
 *
 * Fail mode (per plan §RMS coverage guard): if [config.rmsUrl] is unset, all calls return
 * `emptyList()` and downstream tests should treat the component as "no real versions". The
 * smoke-mode `each component must have ≥1 real version` rule is enforced by [coverage] check
 * called from the test that uses VersionSampler.
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
     * Fetch up to [limit] real release versions for [componentName]. Cached per call regardless
     * of [limit] (first caller wins). Returns empty list when RMS is unreachable / unset / has
     * no releases for the component.
     */
    fun versionsFor(componentName: String, limit: Int = 5, rmsUrl: String? = CompatConfig.load().rmsUrl): List<String> {
        if (rmsUrl.isNullOrBlank()) return emptyList()
        return cache.computeIfAbsent(componentName) {
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
