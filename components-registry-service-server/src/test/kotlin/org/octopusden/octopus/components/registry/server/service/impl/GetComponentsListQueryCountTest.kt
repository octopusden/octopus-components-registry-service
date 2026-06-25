package org.octopusden.octopus.components.registry.server.service.impl

import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.BATCH_FETCH_SIZE
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.ToolEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * Batch-boundary query-count guard for the full unpaged list path
 * `GET /rest/api/3/components` → `DatabaseComponentRegistryResolver.getComponents()` (GH #365).
 *
 * The sibling [DatabaseComponentRegistryResolverQueryCountTest] proves the *single-component-batch*
 * read paths have no N+1, but it does so with fixtures of 2 and 8 — **both below** the
 * `@BatchSize` threshold — so it structurally cannot detect a wrongly-sized batch: at any
 * `@BatchSize >= 8` its two fixtures each load every role in exactly one `IN` select. The pre-#365
 * batch size was `100` while production carries ~988 components / ~3k config rows, so each lazy
 * role actually took `ceil(owners / 100)` `IN` selects — a per-request statement storm that scaled
 * with the data and dominated latency against a remote DB.
 *
 * This test exercises the same `getComponents()` mapper walk at **two fixture sizes that both
 * exceed the old batch size of 100** ([SMALL] = 150, [LARGE] = 500) and asserts the
 * JDBC-statement count is **equal across the two sizes** (plus a fixed upper bound). Mechanics:
 *
 *  - component-owned roles (`configurations`, `artifactIds`, the empty `securityGroups` /
 *    `docLinks` / `releaseManagers` / `securityChampions` / `teamcityProjects` / `labelJunctions`,
 *    and the `parentComponent` to-one) have one owner per component → 150 vs 500 owners.
 *  - config-owned roles (`vcsEntries`, `mavenArtifacts`, `fileUrlArtifacts`, `dockerImages`,
 *    `packages`, `requiredToolJunctions`, `buildToolBeans`) have one owner per config row. Each
 *    component carries **exactly 2** config rows (BASE + one SCALAR_OVERRIDE), so 300 vs 1000 owners.
 *
 * [LARGE] is pinned so the largest per-role owner count (1000 config rows) **equals** the post-fix
 * `@BatchSize` (1000): every role loads in exactly one `IN` select at both sizes → counts are equal
 * (GREEN). Under the old size 100 the role counts diverge
 * (`ceil(500/100)=5 != ceil(150/100)=2`; `ceil(1000/100)=10 != ceil(300/100)=3`) → counts differ
 * (RED) — and, critically, **any** batch size below 1000 (i.e. below the ~988 production component
 * count) splits the LARGE config-owned roles into a second batch, so this test fails for any
 * regression that drops the batch size below the production scale, not just for the old `100`.
 *
 * NOTE: the test class is intentionally **not** `@Transactional`. Each `resolver.getComponents()`
 * call must open its own read-only transaction/session through the resolver's class-level
 * `@Transactional(readOnly = true)` proxy, so the warm-up call in [measure] does not leave the
 * measured call reading a warm first-level (session) cache (which would record ~0 statements).
 *
 * Integration test: real PostgreSQL via Testcontainers, `dbTest` gradle task (`@Tag("integration")`).
 */
@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
@Timeout(300)
@Tag("integration")
class GetComponentsListQueryCountTest {

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var toolRepository: ToolRepository

    @Autowired
    private lateinit var requiredToolRepository: ComponentRequiredToolRepository

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    init {
        val testResourcesPath =
            Paths.get(
                GetComponentsListQueryCountTest::class.java.getResource("/expected-data")!!.toURI(),
            ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private val statistics get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    @BeforeEach
    fun cleanDatabase() {
        // Required-tool junctions are not cascade-deleted from the component (their @OneToMany has
        // no cascade) and FK both the config and the tool — delete junctions first, then components
        // (CASCADE-ALL removes configurations + their children), then the now-unreferenced tools.
        requiredToolRepository.deleteAll()
        componentRepository.deleteAll()
        toolRepository.deleteAll()
    }

    /**
     * Persist [count] components, each with exactly 2 config rows (a BASE carrying representative
     * children + one SCALAR_OVERRIDE on `build.javaVersion`), one component-level artifactId, a
     * required tool on the base config, and (for index > 0) a `parentComponent` reference to
     * index 0 — so both the to-one and every collection role the mapper walks are exercised at a
     * size that scales with the fixture.
     */
    private fun persistComponents(count: Int) {
        var parent: ComponentEntity? = null
        repeat(count) { index ->
            val component = ComponentEntity(componentKey = "gc-comp-$index", archived = false)
            component.parentComponent = parent
            val base = ComponentConfigurationEntity(
                component = component,
                versionRange = VERSION_RANGE,
                overriddenAttribute = null,
                rowType = "BASE",
                buildSystem = "MAVEN", // BASE rows require build_system NOT NULL (schema CHECK)
                jiraProjectKey = "GCPROJ$index",
            )
            base.mavenArtifacts.add(
                DistributionMavenArtifactEntity(
                    componentConfiguration = base,
                    groupPattern = "com.example.gc",
                    artifactPattern = "lib-$index",
                    sortOrder = 0,
                ),
            )
            base.dockerImages.add(
                DistributionDockerImageEntity(
                    componentConfiguration = base,
                    imageName = "gc-image-$index",
                    sortOrder = 0,
                ),
            )
            base.vcsEntries.add(
                VcsSettingsEntryEntity(
                    componentConfiguration = base,
                    name = "main",
                    vcsPath = "ssh://git/gc-$index.git",
                    branch = "master",
                    repositoryType = "GIT",
                    sortOrder = 0,
                ),
            )
            val override = ComponentConfigurationEntity(
                component = component,
                versionRange = VERSION_RANGE,
                overriddenAttribute = "build.javaVersion",
                rowType = "SCALAR_OVERRIDE",
                javaVersion = "17",
            )
            component.configurations.add(base)
            component.configurations.add(override)
            component.artifactIds.add(
                ComponentArtifactIdEntity(
                    component = component,
                    groupPattern = "com.example.gc",
                    artifactPattern = "lib-$index",
                    sortOrder = 0,
                ),
            )
            val saved = componentRepository.save(component)

            val toolName = "gc-tool-$index"
            toolRepository.save(ToolEntity(name = toolName))
            requiredToolRepository.save(
                ComponentRequiredToolEntity(
                    componentConfigurationId = saved.configurations.first { it.rowType == "BASE" }.id!!,
                    toolName = toolName,
                ),
            )
            if (index == 0) parent = saved
        }
    }

    /** Statement count of [block], measured in a session with a warm metamodel. */
    private fun measure(block: () -> Unit): Long {
        block() // warm-up — exclude one-time costs (metamodel init, etc.)
        statistics.clear()
        block()
        return statistics.prepareStatementCount
    }

    @Test
    @DisplayName("getComponents() statement count is independent of component count above the batch boundary")
    fun getComponentsListHasNoBatchBoundaryStorm() {
        // Invariant the equality assertion depends on: at LARGE the largest per-role owner count
        // is the config-row count (2 * LARGE = 1000), pinned to EXACTLY BATCH_FETCH_SIZE. With the
        // `<=` bound, any BATCH_FETCH_SIZE below 1000 (i.e. below the ~988 production component
        // count this fix exists to cover) pushes config-owned roles to a second IN batch at LARGE
        // but not at SMALL → the equality assertion below fails. This makes the test a real guard
        // of the design decision "batch size must cover the production component count", not just a
        // tautology over the current constant.
        assertTrue(
            LARGE * 2 <= BATCH_FETCH_SIZE,
            "LARGE fixture config-owner count (${LARGE * 2}) must be <= BATCH_FETCH_SIZE ($BATCH_FETCH_SIZE) " +
                "so every role loads in a single IN; a smaller batch size must not pass this guard",
        )

        persistComponents(SMALL)
        val small = measure { resolver.getComponents() }

        cleanDatabase()
        persistComponents(LARGE)
        val large = measure { resolver.getComponents() }

        // Sanity: the mapper actually produced every component (so the roles were really walked).
        assertEquals(LARGE, resolver.getComponents().size, "getComponents() must return every persisted component")

        log.info("getComponents() statement counts — SMALL($SMALL)=$small, LARGE($LARGE)=$large")

        assertEquals(
            small,
            large,
            "getComponents() must issue the same number of SQL statements for $SMALL and $LARGE components " +
                "(a difference means a lazy role's @BatchSize is below the production owner count, " +
                "re-introducing the #365 per-request round-trip storm). small=$small large=$large",
        )
        assertTrue(
            large <= STATEMENT_BOUND,
            "getComponents() issued $large statements for $LARGE components, expected <= $STATEMENT_BOUND",
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(GetComponentsListQueryCountTest::class.java)

        // Both deliberately ABOVE the old @BatchSize(100); LARGE pinned so the largest per-role
        // owner count (config rows = 2 * LARGE = 1000) equals the post-fix @BatchSize(1000) exactly.
        // At the correct size every role loads in a single IN at both sizes; any batch size below
        // 1000 (below the production component count) splits the LARGE config-owned roles into a
        // second batch and breaks the equality assertion — the real signal this test guards.
        private const val SMALL = 150
        private const val LARGE = 500

        private const val VERSION_RANGE = "[1.0,2.0)"

        // ~15 walked roles, each one IN select + findAll + a little fixed overhead. Calibrated
        // from the post-fix baseline with generous headroom for Hibernate batch-loader drift.
        private const val STATEMENT_BOUND = 40L

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
