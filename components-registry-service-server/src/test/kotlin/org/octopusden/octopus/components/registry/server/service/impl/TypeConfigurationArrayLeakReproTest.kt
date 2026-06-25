package org.octopusden.octopus.components.registry.server.service.impl

import jakarta.persistence.EntityManagerFactory
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.type.spi.TypeConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Phase B.2 — PROVE the `org.hibernate.type.BasicArrayType` retention leak that MAT
 * flagged in the real OOM heap dump (~875k array types retained by a ConcurrentHashMap
 * hanging off ONE TypeConfiguration). RED reproduction on the deployed-incident-equivalent
 * commit (b2647eda, Hibernate 6.4.1.Final, BATCH_FETCH_SIZE=100).
 *
 * Mechanism under test (HHH-18551, fixed in Hibernate 6.6.2):
 * `AbstractArrayJavaType#getRecommendedJdbcType` mints a FRESH non-deduplicating
 * `BasicTypeImpl` (element BasicType) per call and feeds it to the array-JdbcType
 * constructor + BasicTypeRegistry.resolve. Because the element BasicType identity is
 * fresh each time, the per-TypeConfiguration registries never coalesce — they grow by
 * one entry per resolution of an array-bearing query parameter.
 *
 * This test does NOT assume which registry grows. It walks
 * `EntityManagerFactory -> SessionFactoryImplementor.getTypeConfiguration()` and
 * snapshots the size of EVERY internal Map on the TypeConfiguration object graph by
 * reflection (basicTypeByJavaType, basicTypeRegistry.registryValues / typesByName,
 * jdbcTypeRegistry.descriptorMap / descriptorConstructorMap, javaTypeRegistry, etc.),
 * before/after a warmup and again after N iterations of the hot read path, and reports
 * the per-iteration growth of each. If no map grows, that is itself reported (the assert
 * fails loudly rather than silently passing).
 *
 * Hot path: `componentRepository.findAll()` + a full walk of every `@BatchSize`
 * association — this is exactly what `DatabaseComponentRegistryResolver.getComponents()`
 * drives (MAT's GC-root path runs through the batch-entity-select-fetch a `findAll`
 * triggers). The fixture wires a `parentComponent` UUID FK on every non-root component
 * so the to-one `@BatchSize` multi-key load (the prime `uuid[]`-array-bind suspect) is
 * exercised.
 *
 * Logging: `org.hibernate.SQL=TRACE` + `org.hibernate.orm.jdbc.bind=TRACE` are turned on
 * via @DynamicPropertySource so the captured test log shows the exact SQL and the bound
 * parameter JDBC types (BasicBinder) for the allocating query.
 *
 * Scaffold mirrors [DatabaseComponentRegistryResolverQueryCountTest].
 */
@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
@Timeout(600)
@Tag("integration")
class TypeConfigurationArrayLeakReproTest {

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val txTemplate by lazy {
        TransactionTemplate(transactionManager).apply { isReadOnly = true }
    }

    init {
        // application-common.yml resolves components-registry.work-dir / groovy-path from this
        // env-backed property; without it the context fails to bind. Mirrors the QueryCountTest.
        val testResourcesPath =
            java.nio.file.Paths.get(
                TypeConfigurationArrayLeakReproTest::class.java.getResource("/expected-data")!!.toURI(),
            ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private val typeConfiguration: TypeConfiguration
        get() = entityManagerFactory
            .unwrap(SessionFactoryImplementor::class.java)
            .typeConfiguration

    @BeforeEach
    fun cleanDatabase() {
        componentRepository.deleteAll()
    }

    /**
     * Persist [count] components. Index 0 is a parent; every other component references it
     * via the LAZY `@ManyToOne parentComponent` (a UUID FK) so the to-one `@BatchSize`
     * multi-key load is exercised on each `findAll()`. Each component also carries a BASE
     * config (with a docker image), an artifactId and a Jira project key so the full
     * `toEscrowModule` walk has data to traverse — mirroring the production read path.
     */
    private fun persistComponents(count: Int) {
        var parent: ComponentEntity? = null
        repeat(count) { index ->
            val component = ComponentEntity(componentKey = "leak-comp-$index", archived = false)
            component.parentComponent = parent
            val base = ComponentConfigurationEntity(
                component = component,
                versionRange = "[1.0,2.0)",
                overriddenAttribute = null,
                rowType = "BASE",
                buildSystem = "MAVEN",
                jiraProjectKey = "LEAKPROJ$index",
            )
            base.dockerImages.add(
                DistributionDockerImageEntity(
                    componentConfiguration = base,
                    imageName = "leak-image-$index",
                    sortOrder = 0,
                ),
            )
            component.configurations.add(base)
            component.artifactIds.add(
                ComponentArtifactIdEntity(
                    component = component,
                    groupPattern = "com.example.leak",
                    artifactPattern = "lib-$index",
                    sortOrder = 0,
                ),
            )
            val saved = componentRepository.save(component)
            if (index == 0) parent = saved
        }
    }

    /**
     * The hot read path: load all components and fully walk every batched association so
     * Hibernate issues the batch-entity-select-fetch (the GC-root path MAT captured). Read
     * in its own transaction-less call; LAZY collections are initialized inside the loop
     * via [touch] while the session is still open (findAll keeps it open for the walk under
     * the open-session-in-view-less test, so we force initialization eagerly here).
     */
    private fun exerciseReadPath() = txTemplate.executeWithoutResult {
        val all = componentRepository.findAll()
        // Force initialization of every @BatchSize role + the parentComponent to-one proxy.
        all.forEach { c ->
            c.parentComponent?.componentKey
            c.configurations.forEach { cfg -> cfg.dockerImages.size }
            c.artifactIds.size
            c.securityGroups.size
            c.teamcityProjects.size
            c.docLinks.size
            c.releaseManagers.size
            c.securityChampions.size
            c.labelJunctions.size
        }
    }

    @Test
    @DisplayName("RED: TypeConfiguration registry grows unbounded per read iteration (HHH-18551 on 6.4.1)")
    fun typeConfigurationRegistryGrowsPerIteration() {
        persistComponents(COMPONENT_COUNT)

        // Warm up: first iterations pay one-time metamodel/loader init costs that are NOT
        // the leak; exclude them from the growth measurement.
        repeat(WARMUP) { exerciseReadPath() }

        val tc = typeConfiguration

        // Snapshot the leaking outer-key (JdbcType) classes BEFORE the loop so we can prove
        // the loop newly minted ARRAY-typed JdbcType keys (the HHH-18551 signature).
        val keysBefore = registryValueOuterKeyClasses(tc)

        val before = snapshotAllMaps(tc)

        repeat(ITERATIONS) { exerciseReadPath() }

        val after = snapshotAllMaps(tc)

        // One extra instrumented iteration with Hibernate SQL + JDBC-bind TRACE turned on
        // programmatically (robust against the test logback config swallowing the
        // logging.level.* dynamic properties). Captures the EXACT allocating SQL and the
        // bound parameter JDBC type so we can read off whether a uuid[] array is bound and
        // from which query. Output goes to the captured test stdout.
        withHibernateTraceLogging {
            println("\n==== INSTRUMENTED read-path iteration (org.hibernate.SQL + jdbc.bind TRACE) ====")
            exerciseReadPath()
        }

        // The outer registryValues map is keyed by JdbcType IDENTITY. The leak adds fresh
        // (non-deduplicating) JdbcType keys, so we classify the outer keys by their class /
        // element type rather than by an equals-collapsing text key. Report how many new
        // outer keys appeared and how many are array-typed (the HHH-18551 signature).
        val classBefore = registryValueOuterKeyClassHistogram(keysBefore)
        val classAfter = registryValueOuterKeyClassHistogram(registryValueOuterKeyClasses(tc))
        val newEntriesReport = StringBuilder(
            "\n==== basicTypeRegistry.registryValues OUTER-KEY (JdbcType) class histogram ====\n",
        )
        newEntriesReport.append("  before:\n")
        classBefore.forEach { (k, v) -> newEntriesReport.append("    %-40s %d%n".format(k, v)) }
        newEntriesReport.append("  after:\n")
        classAfter.forEach { (k, v) -> newEntriesReport.append("    %-40s %d%n".format(k, v)) }
        val arrayClassesAfter = classAfter.filterKeys { it.contains("Array", ignoreCase = true) }
        val arrayClassesBefore = classBefore.filterKeys { it.contains("Array", ignoreCase = true) }
        val arrayGrowth = (arrayClassesAfter.values.sum()) - (arrayClassesBefore.values.sum())
        newEntriesReport.append(
            "  ARRAY-typed outer JdbcType keys: before=${arrayClassesBefore.values.sum()} " +
                "after=${arrayClassesAfter.values.sum()} delta=+$arrayGrowth\n",
        )
        println(newEntriesReport)

        // Report every map and its delta — measured, not inferred.
        val report = StringBuilder()
        report.append(
            "\n==== TypeConfiguration map sizes over N=$ITERATIONS iterations " +
                "(after W=$WARMUP warmup, $COMPONENT_COUNT components) ====\n",
        )
        val deltas = LinkedHashMap<String, Long>()
        for ((path, sizeBefore) in before) {
            val sizeAfter = after[path] ?: -1L
            val delta = sizeAfter - sizeBefore
            deltas[path] = delta
            report.append(
                "  %-72s before=%-8d after=%-8d delta=%+d%n".format(path, sizeBefore, sizeAfter, delta),
            )
        }
        val growing = deltas.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        report.append("\n  GROWING MAPS (delta>0), largest first:\n")
        if (growing.isEmpty()) {
            report.append("    <none> — no TypeConfiguration map grew over the loop\n")
        } else {
            growing.forEach { (path, delta) ->
                report.append(
                    "    %-72s delta=%+d  (%.2f entries / iteration)%n"
                        .format(path, delta, delta.toDouble() / ITERATIONS),
                )
            }
        }

        val maxDelta = deltas.values.maxOrNull() ?: 0L
        report.append("\n  largest per-iteration growth: %.2f entries / iteration (maxDelta=%d over %d iters)%n"
            .format(maxDelta.toDouble() / ITERATIONS, maxDelta, ITERATIONS))
        println(report)

        // Durable regression guard (GREEN): after the one-time warmup, NO TypeConfiguration
        // map may keep growing per read iteration. The HHH-18551 leak on Hibernate 6.4.1
        // adds ~9 fresh ArrayJdbcType entries per iteration to basicTypeRegistry.registryValues
        // (measured: +540 over 60 iters); the 6.6.x fix holds it flat (measured: +0). The
        // bound is set well below the leak rate so it cleanly separates fixed (≈0) from
        // leaking (≈9/iter): a tiny tolerance absorbs any benign one-time lazy registration
        // that slips past the warmup, while still failing hard on the unbounded leak.
        //
        // This assertion therefore FAILS on 6.4.1 (RED — the leak) and PASSES on 6.6.x
        // (GREEN — the fix), making it a forward regression guard against re-introducing
        // the leak (e.g. a Spring Boot downgrade that drags Hibernate back below 6.6.2).
        assertTrue(
            maxDelta <= GROWTH_TOLERANCE,
            "TypeConfiguration registry grew by maxDelta=$maxDelta over $ITERATIONS iterations " +
                "(tolerance=$GROWTH_TOLERANCE). A large delta is the HHH-18551 array-type leak " +
                "(unfixed on Hibernate < 6.6.2). Per-map detail:$report",
        )
    }

    /**
     * Raise the Hibernate SQL + JDBC-bind loggers to TRACE for the duration of [block],
     * then restore. Done reflectively against logback (the runtime logging backend) so it
     * works regardless of how the test logging config is wired — more reliable than the
     * logging.level.* Spring property, which the test logback config can override.
     */
    private fun withHibernateTraceLogging(block: () -> Unit) {
        val loggerNames = listOf(
            "org.hibernate.SQL",
            "org.hibernate.orm.jdbc.bind",
            "org.hibernate.type.descriptor.sql.BasicBinder",
        )
        val factory = org.slf4j.LoggerFactory.getILoggerFactory()
        val getLogger = factory.javaClass.getMethod("getLogger", String::class.java)
        val saved = mutableListOf<Pair<Any, Any?>>()
        runCatching {
            for (name in loggerNames) {
                val logger = getLogger.invoke(factory, name)
                val levelClass = Class.forName("ch.qos.logback.classic.Level")
                val trace = levelClass.getField("TRACE").get(null)
                val getLevel = logger.javaClass.getMethod("getLevel")
                val setLevel = logger.javaClass.getMethod("setLevel", levelClass)
                saved.add(logger to getLevel.invoke(logger))
                setLevel.invoke(logger, trace)
            }
        }
        try {
            block()
        } finally {
            runCatching {
                val levelClass = Class.forName("ch.qos.logback.classic.Level")
                for ((logger, level) in saved) {
                    val setLevel = logger.javaClass.getMethod("setLevel", levelClass)
                    setLevel.invoke(logger, level)
                }
            }
        }
    }

    /**
     * The list of outer-key (JdbcType) class names of `registryValues`, one element per
     * outer entry (so a histogram reveals how many ArrayJdbcType keys exist). For array
     * JdbcTypes we append the element JdbcType class for extra fidelity.
     */
    @Suppress("UNCHECKED_CAST")
    private fun registryValueOuterKeyClasses(tc: TypeConfiguration): List<String> {
        val registry = tc.basicTypeRegistry
        val field = registry.javaClass.getDeclaredField("registryValues").apply { isAccessible = true }
        val outer = field.get(registry) as Map<Any, *>
        return outer.keys.map { it.javaClass.name.substringAfterLast('.') }
    }

    private fun registryValueOuterKeyClassHistogram(classes: List<String>): Map<String, Int> =
        classes.groupingBy { it }.eachCount().toSortedMap()

    /**
     * Reflectively snapshot the size of every `Map`/`Collection` field reachable from the
     * TypeConfiguration object graph (depth-bounded, identity-deduped). Returns a
     * field-path -> size map. We snapshot ALL of them rather than guess the leaking field,
     * because the exact growing field name across Hibernate versions is not known a priori.
     */
    private fun snapshotAllMaps(root: Any): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        walk(root, "TypeConfiguration", out, seen, depth = 0)
        return out
    }

    private fun walk(obj: Any, path: String, out: MutableMap<String, Long>, seen: MutableSet<Any>, depth: Int) {
        if (depth > MAX_DEPTH || !seen.add(obj)) return
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val value =
                    try {
                        field.isAccessible = true
                        field.get(obj)
                    } catch (_: Throwable) {
                        continue
                    } ?: continue
                val childPath = "$path.${field.name}"
                when (value) {
                    is Map<*, *> -> {
                        out[childPath] = value.size.toLong()
                        // Recurse into nested registry objects that themselves hold maps
                        // (e.g. BasicTypeRegistry.registryValues -> inner per-JdbcType maps
                        //  are counted via the outer size; we still descend one level into
                        //  registry holder objects below).
                    }
                    is Collection<*> -> out[childPath] = value.size.toLong()
                    else -> {
                        // Descend into Hibernate registry/holder objects so nested maps
                        // (JdbcTypeRegistry.descriptorMap, BasicTypeRegistry.registryValues,
                        //  JavaTypeRegistry.descriptorsByType, basicTypeByJavaType) are reached.
                        val pkg = value.javaClass.name
                        if (pkg.startsWith("org.hibernate.type") || pkg.startsWith("org.hibernate.metamodel")) {
                            walk(value, childPath, out, seen, depth + 1)
                        }
                    }
                }
            }
            cls = cls.superclass
        }
    }

    companion object {
        // Above @BatchSize(100)? No — kept under so each role loads in one IN batch, matching
        // the production batch size = 100. The leak is per-resolution, not per-batch, so a
        // modest component count is enough; iteration count drives the growth signal.
        private const val COMPONENT_COUNT = 20
        private const val WARMUP = 3
        private const val ITERATIONS = 60
        private const val MAX_DEPTH = 4

        // Max tolerated per-loop growth of ANY TypeConfiguration map after warmup. Set well
        // below the measured 6.4.1 leak rate (+540 over 60 iters ≈ 9/iter) and well above the
        // measured 6.6.x fixed delta (+0), so it cleanly distinguishes leaking from fixed.
        private const val GROWTH_TOLERANCE = 15L

        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            // Capture the exact allocating SQL and the bound parameter JDBC types so the log
            // shows whether a uuid[] array is bound (BasicBinder TRACE) and from which query.
            registry.add("logging.level.org.hibernate.SQL") { "TRACE" }
            registry.add("logging.level.org.hibernate.orm.jdbc.bind") { "TRACE" }
            registry.add("logging.level.org.hibernate.type.descriptor.sql.BasicBinder") { "TRACE" }
        }
    }
}
