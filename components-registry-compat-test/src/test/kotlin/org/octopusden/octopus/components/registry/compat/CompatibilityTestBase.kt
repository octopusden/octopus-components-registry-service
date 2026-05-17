package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.Semaphore

/**
 * Common scaffolding for compat tests.
 *
 * Loads compat config, constructs raw + typed clients for baseline and candidate,
 * and exposes helpers for recording diffs and execution entries.
 *
 * The class-level `@Tag("http")` propagates to every subclass via JUnit Jupiter
 * tag inheritance, and is the polarity anchor for the test-task split in
 * `build.gradle`: `:test` runs `includeTags 'http'`, `:unitTest` runs
 * `excludeTags 'http'`. Concretely this means a future pure-unit test class
 * that forgets to add `@Tag("unit")` still lands in `:unitTest` (the
 * PR-time gate) instead of silently disappearing into the URL-gated `:test`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("http")
abstract class CompatibilityTestBase {
    protected val log = LoggerFactory.getLogger(this::class.java)
    protected val config: CompatConfig = CompatConfig.load()

    protected lateinit var baselineRaw: RawHttpClient
    protected lateinit var candidateRaw: RawHttpClient
    protected lateinit var baselineTyped: ComponentsRegistryServiceClient
    protected lateinit var candidateTyped: ComponentsRegistryServiceClient

    /** Concurrency guard — caps in-flight requests against each remote stand. */
    private val limiter: Semaphore by lazy { Semaphore(config.parallelism.coerceAtLeast(1)) }

    @BeforeAll
    fun verifyConfigAndInit() {
        config.explainInvalid()?.let { error("Invalid compat configuration: $it") }
        assumeTrue(config.active, "compat URLs not configured — skipping (set -Pcompat.baseline.url and -Pcompat.candidate.url to enable)")
        // `assumeTrue(config.active, ...)` above guarantees both URLs are non-null;
        // use `!!` (matching the RawHttpClient lines) instead of relying on the
        // Java-interop platform type (`String!`) for the UrlProvider override.
        val baselineUrl = config.baselineUrl!!
        val candidateUrl = config.candidateUrl!!
        baselineRaw = RawHttpClient(baselineUrl)
        candidateRaw = RawHttpClient(candidateUrl)
        baselineTyped = ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String = baselineUrl
            },
        )
        candidateTyped = ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String = candidateUrl
            },
        )
    }

    /**
     * Fetch both stands (GET) — limited by [limiter] against per-stand concurrency.
     */
    protected fun fetchPair(path: String): Pair<RawResponse, RawResponse> {
        val b = guarded { baselineRaw.get(path) }
        val c = guarded { candidateRaw.get(path) }
        return b to c
    }

    /** Post the same JSON body to both stands and return the response pair. */
    protected fun postJsonPair(path: String, body: Any): Pair<RawResponse, RawResponse> {
        val b = guarded { baselineRaw.postJson(path, body) }
        val c = guarded { candidateRaw.postJson(path, body) }
        return b to c
    }

    /** PUT to both stands (no body). */
    protected fun putPair(path: String): Pair<RawResponse, RawResponse> {
        val b = guarded { baselineRaw.put(path) }
        val c = guarded { candidateRaw.put(path) }
        return b to c
    }

    /**
     * Compare a pair of responses: status -> headers (allow-list) -> JSON shape.
     * Records diffs to [DiffCollector]. Returns the resulting categories observed.
     *
     * [queryParams] threads through to every emitted [DiffRecord] so that cases
     * differing only in query parameters (e.g. `?ignore-required=true` vs `false`)
     * produce distinguishable records in `summary.md` and in known-delta matching.
     *
     * Implementation lives in [Comparators.compareRaw] so the semantics are
     * unit-testable without the HTTP/Spring scaffolding this base class needs
     * at runtime — see `ComparatorLogicTest`.
     */
    protected fun compareRaw(
        endpoint: String,
        pathParams: Map<String, String>,
        baseline: RawResponse,
        candidate: RawResponse,
        headerAllowList: Set<String> = setOf("Content-Type"),
        queryParams: Map<String, String> = emptyMap(),
    ): List<DiffClassifier> = Comparators.compareRaw(
        endpoint = endpoint,
        pathParams = pathParams,
        baseline = baseline,
        candidate = candidate,
        headerAllowList = headerAllowList,
        queryParams = queryParams,
    )

    /**
     * Run [block] under the parallel-request limiter.
     */
    private fun <T> guarded(block: () -> T): T {
        limiter.acquire()
        try {
            return block()
        } finally {
            limiter.release()
        }
    }

    /**
     * Convenience for recursive DTO comparison via AssertJ — typed layer.
     * Wraps `assertThat(...).usingRecursiveComparison().ignoringCollectionOrder().isEqualTo(...)`
     * with per-field normalizers wired in [Comparators.compareDto] (see
     * [GavCsvComparator] for the trailing-comma rule). Diffs are funnelled
     * through DiffCollector instead of a thrown AssertionError so the run
     * can collect-all before failing.
     */
    protected fun <T : Any> compareDto(
        endpoint: String,
        pathParams: Map<String, String>,
        baseline: T?,
        candidate: T?,
        queryParams: Map<String, String> = emptyMap(),
    ) = Comparators.compareDto(
        endpoint = endpoint,
        pathParams = pathParams,
        baseline = baseline,
        candidate = candidate,
        queryParams = queryParams,
    )

    /**
     * Helper used by every test method to log execution and report diffs in one place.
     *
     * `diffCount = diffsAfter - diffsBefore` where both endpoints come from
     * [DiffCollector.count], which is per-**thread** (not process-wide). JUnit 5
     * concurrent execution keeps one test method on one thread for its lifetime,
     * so a before/after delta captured inside a single test method counts only
     * that method's records — it is not cross-polluted by other test methods
     * recording in parallel against the shared `DiffCollector.records` queue.
     */
    protected fun logExecution(
        endpoint: String,
        pathParams: Map<String, String>,
        queryParams: Map<String, String> = emptyMap(),
        baseline: RawResponse,
        candidate: RawResponse,
        layer: String,
        diffsBefore: Int,
        diffsAfter: Int,
    ) {
        ExecutionLogger.log(
            ExecutionEntry(
                endpoint = endpoint,
                pathParams = pathParams,
                queryParams = queryParams,
                baselineStatus = baseline.status,
                candidateStatus = candidate.status,
                baselineMs = baseline.durationMs,
                candidateMs = candidate.durationMs,
                layer = layer,
                diffCount = diffsAfter - diffsBefore,
            ),
        )
    }

    protected fun jsonOrNull(node: JsonNode?): String? = node?.toString()

    /**
     * Sentinel argument emitted by `@MethodSource` providers when the smoke set
     * (or its version expansion) is empty. JUnit's `@ParameterizedTest` would
     * otherwise fail with `initializationError: You must configure at least one
     * set of arguments`, which obscures the underlying configuration problem
     * (no `COMPAT_SMOKE_COMPONENTS`, no `compat.rms.url`, etc.).
     *
     * Test bodies pass the received argument(s) to [skipIfNoSmokeConfig] before
     * any real work — that skips the test via `Assumptions.assumeTrue` with a
     * clear message.
     */
    protected fun skipIfNoSmokeConfig(vararg args: Any?) {
        assumeTrue(
            args.none { it == NO_SMOKE_CONFIG },
            "compat smoke list (and/or version sample) is empty — provide " +
                "COMPAT_SMOKE_COMPONENTS / -Pcompat.smoke-components, and " +
                "compat.rms.url for version-driven endpoints; see README.md",
        )
    }

    /**
     * Build a `Stream<Arguments>` for a single-string `@MethodSource`. Falls back
     * to a single sentinel argument when [items] is empty so JUnit doesn't fail
     * at discovery time; the test body must call [skipIfNoSmokeConfig] first.
     */
    protected fun singleArgsOrSentinel(items: List<String>): java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> {
        if (items.isEmpty()) {
            return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(NO_SMOKE_CONFIG),
            )
        }
        return items.map { org.junit.jupiter.params.provider.Arguments.of(it) }.stream()
    }

    /**
     * Variant for two-arg providers (e.g. `(component, version)`). Same contract
     * as [singleArgsOrSentinel].
     */
    protected fun pairArgsOrSentinel(
        pairs: List<Pair<String, String>>,
    ): java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> {
        if (pairs.isEmpty()) {
            return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(NO_SMOKE_CONFIG, NO_SMOKE_CONFIG),
            )
        }
        return pairs.map { (a, b) -> org.junit.jupiter.params.provider.Arguments.of(a, b) }.stream()
    }

    companion object {
        const val NO_SMOKE_CONFIG: String = "__no-smoke-config__"
    }

    /**
     * Resolve the list of smoke component names.
     *
     * Resolution order (first non-empty wins):
     *  1. `-Pcompat.smoke-components=name1,name2,…` Gradle property
     *  2. `COMPAT_SMOKE_COMPONENTS=name1,name2,…` env var
     *  3. `/smoke-components.txt` test resource (lines starting with `#` ignored)
     *
     * Real production component names are confidential and must not live in
     * the repo: keep `/smoke-components.txt` synthetic (or empty) and feed the
     * actual list via the env / Gradle property at runtime. Result is capped at
     * [CompatConfig.maxComponents] when set.
     */
    protected fun smokeComponents(): List<String> {
        val override =
            System.getProperty("compat.smoke-components")
                ?: System.getenv("COMPAT_SMOKE_COMPONENTS")
        val list =
            if (!override.isNullOrBlank()) {
                override
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            } else {
                this::class.java.getResourceAsStream("/smoke-components.txt")
                    ?.bufferedReader()
                    ?.useLines { lines ->
                        lines
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .toList()
                    }
                    ?: emptyList()
            }
        val cap = config.maxComponents
        return if (cap != null) list.take(cap) else list
    }
}

/**
 * JSON parsed from the body, decoded as the given DTO type. Throws if not JSON.
 *
 * Top-level inline so reified-T works without inline-on-protected-member restrictions.
 */
inline fun <reified T> RawResponse.asDto(): T {
    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    return mapper.readValue(bodyBytes, T::class.java)
}
