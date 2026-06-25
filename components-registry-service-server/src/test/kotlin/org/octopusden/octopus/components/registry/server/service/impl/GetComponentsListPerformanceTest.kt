package org.octopusden.octopus.components.registry.server.service.impl

import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths
import kotlin.system.measureTimeMillis

/**
 * Production-scale SLA/regression guard for `DatabaseComponentRegistryResolver.getComponents()`
 * backing `GET /rest/api/3/components` (GH #365).
 *
 * Seeds ~[COMPONENT_COUNT] components / ~3× that many configuration rows with representative child
 * collections, warms the JVM + Hibernate metamodel, then times `getComponents()` over several
 * iterations and asserts the **median** wall time stays under [MEDIAN_CEILING_MS].
 *
 * **This test is NON-GATING for normal CI.** It is `@Tag("performance")` (NOT `"integration"`), so
 * it is excluded from both the fast `test` gate and the `dbTest` correctness gate; it runs only
 * under the dedicated `perfTest` gradle task. The deterministic [GetComponentsListQueryCountTest]
 * (statement-count batch-boundary) is the hard correctness gate — this one is a calibration/SLA
 * report whose timing ceiling may be tuned per environment.
 *
 * Caveat: Testcontainers runs a **local** Postgres, so even the pre-#365 code may pass this ceiling
 * despite ~3.5 s against QA's remote DB — the wall-time win there comes mostly from collapsing the
 * batch round-trips (proven by the query-count test), not from anything observable on a local DB.
 * The ceiling here mainly guards against a gross CPU/allocation regression in the mapper.
 */
@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
@Timeout(600)
@Tag("performance")
class GetComponentsListPerformanceTest {

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    init {
        val testResourcesPath =
            Paths.get(
                GetComponentsListPerformanceTest::class.java.getResource("/expected-data")!!.toURI(),
            ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private val statistics get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    @BeforeEach
    fun seed() {
        componentRepository.deleteAll()
        val batch = ArrayList<ComponentEntity>(BATCH_PERSIST)
        repeat(COMPONENT_COUNT) { index ->
            batch.add(buildComponent(index))
            if (batch.size == BATCH_PERSIST) {
                componentRepository.saveAll(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) componentRepository.saveAll(batch)
    }

    /**
     * One component with 3 config rows (BASE + 2 SCALAR_OVERRIDE) — base carries a maven artifact,
     * docker image and vcs entry — plus a component-level artifactId. No `parentComponent`/tool
     * junctions here (those are covered by the query-count test); this fixture targets volume:
     * ~[COMPONENT_COUNT] components × 3 configs ≈ 3k config rows with their child collections.
     */
    private fun buildComponent(index: Int): ComponentEntity {
        val component = ComponentEntity(
            componentKey = "perf-comp-$index",
            componentOwner = "owner-${index % 50}",
            archived = false,
        )
        val base = ComponentConfigurationEntity(
            component = component,
            versionRange = "[1.0,2.0)",
            overriddenAttribute = null,
            rowType = "BASE",
            buildSystem = "MAVEN",
            javaVersion = "17",
            jiraProjectKey = "PERF$index",
        )
        base.mavenArtifacts.add(
            DistributionMavenArtifactEntity(
                componentConfiguration = base,
                groupPattern = "com.example.perf",
                artifactPattern = "lib-$index",
                sortOrder = 0,
            ),
        )
        base.dockerImages.add(
            DistributionDockerImageEntity(
                componentConfiguration = base,
                imageName = "perf-image-$index",
                sortOrder = 0,
            ),
        )
        base.vcsEntries.add(
            VcsSettingsEntryEntity(
                componentConfiguration = base,
                name = "main",
                vcsPath = "ssh://git/perf-$index.git",
                branch = "master",
                repositoryType = "GIT",
                sortOrder = 0,
            ),
        )
        component.configurations.add(base)
        component.configurations.add(
            ComponentConfigurationEntity(
                component = component,
                versionRange = "[2.0,3.0)",
                overriddenAttribute = "build.javaVersion",
                rowType = "SCALAR_OVERRIDE",
                javaVersion = "21",
            ),
        )
        component.configurations.add(
            ComponentConfigurationEntity(
                component = component,
                versionRange = "[3.0,4.0)",
                overriddenAttribute = "build.javaVersion",
                rowType = "SCALAR_OVERRIDE",
                javaVersion = "25",
            ),
        )
        component.artifactIds.add(
            ComponentArtifactIdEntity(
                component = component,
                groupPattern = "com.example.perf",
                artifactPattern = "lib-$index",
                sortOrder = 0,
            ),
        )
        return component
    }

    @Test
    @DisplayName("getComponents() at ~1k components stays under the SLA ceiling (median wall time)")
    fun getComponentsListUnderSlaCeiling() {
        // Warm up the JVM + Hibernate metamodel + Spring proxy — discard.
        repeat(WARMUP_ITERATIONS) { resolver.getComponents() }

        // One measured iteration with statistics on, for the reported statement count.
        statistics.clear()
        val firstCount = resolver.getComponents().size
        val statementCount = statistics.prepareStatementCount

        assertEquals(COMPONENT_COUNT, firstCount, "getComponents() must return every seeded component")

        // Representative correctness check (prove we mapped real data, not just counted fast).
        val sample = resolver.getComponents().first { it.moduleName == "perf-comp-7" }
        assertNotNull(sample.moduleConfigurations.firstOrNull(), "sample component must have configurations")
        assertEquals(
            "owner-7",
            sample.moduleConfigurations.first { it.componentOwner != null }.componentOwner,
            "sample component owner must round-trip",
        )

        val elapsed = LongArray(MEASURED_ITERATIONS) { measureTimeMillis { resolver.getComponents() } }
        val median = elapsed.sorted()[MEASURED_ITERATIONS / 2]

        log.info(
            "getComponents() perf — components={}, statements={}, iterations={}, elapsedMs={}, medianMs={}",
            COMPONENT_COUNT, statementCount, MEASURED_ITERATIONS, elapsed.toList(), median,
        )

        assertTrue(
            median < MEDIAN_CEILING_MS,
            "getComponents() median was ${median}ms for $COMPONENT_COUNT components " +
                "(ceiling ${MEDIAN_CEILING_MS}ms); statements=$statementCount, iterations=${elapsed.toList()}",
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(GetComponentsListPerformanceTest::class.java)

        private const val COMPONENT_COUNT = 1000
        private const val BATCH_PERSIST = 100
        private const val WARMUP_ITERATIONS = 2
        private const val MEASURED_ITERATIONS = 5

        // Generous local-DB ceiling. Calibrate against several CI-like runs before tightening;
        // this guards against a gross mapper CPU/allocation regression, not remote-DB latency
        // (which the query-count test covers structurally).
        private const val MEDIAN_CEILING_MS = 2000L

        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { "true" }
        }
    }
}
