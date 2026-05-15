package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Raw HTTP layer for compat tests.
 *
 * Captures status, body (as bytes + parsed JsonNode), and selected headers.
 * Used directly for status-code/shape diffs, alongside the typed Feign client
 * which is reserved for value-level (DTO recursive) comparison.
 */
class RawHttpClient(
    val baseUrl: String,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun get(path: String): RawResponse = exec(Request.Builder().url(joinUrl(baseUrl, path)).get().build())

    fun postJson(path: String, body: Any): RawResponse {
        val payload = mapper.writeValueAsString(body)
        val req = Request.Builder()
            .url(joinUrl(baseUrl, path))
            .post(payload.toRequestBody(JSON))
            .build()
        return exec(req)
    }

    fun put(path: String): RawResponse =
        exec(Request.Builder().url(joinUrl(baseUrl, path)).put("".toRequestBody(JSON)).build())

    private fun exec(request: Request): RawResponse {
        val started = System.nanoTime()
        return try {
            http.newCall(request).execute().use { resp ->
                val durationMs = (System.nanoTime() - started) / 1_000_000
                val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                val contentType = resp.header("Content-Type")
                val parsed: JsonNode? = if (contentType?.contains("json", ignoreCase = true) == true && bodyBytes.isNotEmpty()) {
                    runCatching { mapper.readTree(bodyBytes) }.getOrNull()
                } else null
                RawResponse(
                    status = resp.code,
                    headers = resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                    bodyBytes = bodyBytes,
                    json = parsed,
                    durationMs = durationMs,
                )
            }
        } catch (ex: java.io.IOException) {
            // Network-level failure (timeout, connection-reset, DNS, etc.). Surface as a synthetic
            // response with status=0 instead of throwing, so the test records a diff and the
            // remaining cases continue. Status=0 is reserved for "transport error" — comparison
            // logic treats it as a STATUS_CODE_DIFF when only one side fails.
            val durationMs = (System.nanoTime() - started) / 1_000_000
            RawResponse(
                status = 0,
                headers = mapOf("X-Compat-Transport-Error" to (ex.message ?: ex.javaClass.simpleName)),
                bodyBytes = ByteArray(0),
                json = null,
                durationMs = durationMs,
            )
        }
    }

    private fun joinUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = path.trimStart('/')
        return "$b/$p"
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class RawResponse(
    val status: Int,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
    val json: com.fasterxml.jackson.databind.JsonNode?,
    val durationMs: Long,
) {
    fun bodyText(): String = String(bodyBytes, Charsets.UTF_8)

    /**
     * Stable headers we actually compare; everything else is diagnostic-only.
     *
     * Keys are normalised to lowercase so the caller can index baseline vs candidate
     * by the same key regardless of which side returned `Content-Type` and which
     * returned `content-type`. Without this, the same header with different case
     * was reported as a HEADER_DIFF.
     */
    fun stableHeaders(allowList: Set<String>): Map<String, String> {
        val allowed = allowList.map(String::lowercase).toSet()
        return headers.entries
            .filter { it.key.lowercase() in allowed }
            .associate { it.key.lowercase() to it.value }
    }
}
