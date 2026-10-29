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
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.ToolEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * Query-count regression guard for the schema-v2 DB read path (GH #321 + #249).
 *
 * Asserts the JDBC-statement count of the batch read endpoints is **independent of
 * the number of components** (and, for docker images, of the number of matched
 * images) — i.e. there is no N+1. Uses Hibernate's
 * [org.hibernate.stat.Statistics.getPrepareStatementCount] (every prepared
 * statement = one round-trip; this is what exposes a lazy-collection N+1, whereas
 * `queryExecutionCount` ignores collection-init selects and would not).
 *
 * Pre-fix (RED): `loadPerComponentArtifactParameters` / `buildImageToComponentMap`
 * lazily dereference each component's bag collections, and `findConfigurationByDockerImage`
 * reloads the component by key per matched image — so the count grows with K.
 * Post-fix (GREEN): `@BatchSize` coalesces the lazy loads into a bounded number of
 * `IN` selects and the docker path resolves off the already-loaded entity — so the
 * count is constant across fixture sizes.
 *
 * Each fixture component carries one BASE config (artifactId + docker image + Jira
 * project key), a parent reference (index > 0 → index 0), and a distinct required
 * tool — so every batched role the mapper walks is exercised, including the two
 * to-one associations whose batch size is read from the TARGET class (`parentComponent`
 * → ComponentEntity, `tool` → ToolEntity). The docker fixture fully resolves, so the
 * test reaches `getResolvedComponentDefinition` (the entity-threaded reload Part B
 * removes), not just the pre-resolve short-circuit.
 *
 * NOTE on the strict-equality assertion: it holds only while every per-role owner
 * count stays **below the `@BatchSize(BATCH_FETCH_SIZE)` threshold** (a single `IN`
 * batch per role). The fixtures here (K = 2 and K = 8) are deliberately well under that
 * bound. If a fixture is ever grown past `BATCH_FETCH_SIZE` owners of any role, switch
 * the assertion to the `ceil(ownerCount / batchSize)`-shaped expectation instead of
 * strict equality. (The batch-boundary behaviour above that threshold is covered by
 * `GetComponentsListQueryCountTest`.)
 *
 * Integration test: real PostgreSQL via Testcontainers, run under the `dbTest`
 * gradle task (`@Tag("integration")`). Scaffold mirrors
 * `AuditLogRepositoryDeleteBySourceTest`.
 */
@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
@Timeout(180)
@Tag("integration")
class DatabaseComponentRegistryResolverQueryCountTest {
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
            Paths
                .get(
                    DatabaseComponentRegistryResolverQueryCountTest::class.java.getResource("/expected-data")!!.toURI(),
                ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private val statistics get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    @BeforeEach
    fun cleanDatabase() {
        // Both target methods do componentRepository.findAll(); start from an empty
        // table so a later (larger) measurement is not polluted by earlier rows.
        // Order matters: the component_required_tools junction is NOT cascade-deleted
        // from the component (its @OneToMany has no cascade) and FKs both the config
        // and the tool, so delete junctions first, then components (CASCADE-ALL removes
        // configurations/artifactIds/dockerImages), then the now-unreferenced tools.
        requiredToolRepository.deleteAll()
        componentRepository.deleteAll()
        toolRepository.deleteAll()
    }

    /**
     * One component with a single BASE configuration carrying an artifactId, a docker
     * image, a Jira project key (so the version resolves and the docker path reaches
     * `getResolvedComponentDefinition`), and a distinct required tool (so the LAZY
     * `ComponentRequiredToolEntity.tool` to-one is exercised). Optionally references
     * [parent] so the LAZY `@ManyToOne` `parentComponent` is also walked.
     *
     * The component + its CASCADE-ALL children (config, artifactId, dockerImage) are
     * saved via the aggregate root; the tool and the non-cascaded required-tool
     * junction are persisted separately (the junction keyed by the saved config id).
     * Returns the managed component entity.
     */
    private fun persistComponent(
        index: Int,
        parent: ComponentEntity?,
    ): ComponentEntity {
        val component = ComponentEntity(componentKey = "qc-comp-$index", archived = false)
        component.parentComponent = parent
        val base = ComponentConfigurationEntity(
            component = component,
            versionRange = VERSION_RANGE,
            overriddenAttribute = null,
            rowType = "BASE",
            buildSystem = "MAVEN",
            // Jira project key → buildJiraComponent emits a JiraComponent (default version
            // formats), so getJiraComponentVersion resolves IMAGE_TAG and the docker path
            // proceeds into getResolvedComponentDefinition (the entity-threaded reload that
            // Part B optimises) instead of short-circuiting at the NotFound catch.
            jiraProjectKey = "QCPROJ$index",
        )
        base.dockerImages.add(
            DistributionDockerImageEntity(
                componentConfiguration = base,
                imageName = "qc-image-$index",
                sortOrder = 0,
            ),
        )
        component.configurations.add(base)
        component.addOwnershipMapping("com.example.qc", "lib-$index")
        val saved = componentRepository.save(component)

        // Distinct required tool per component → the mapper's `junction.tool` access
        // (EntityMappers.toBuildParameters) loads a different ToolEntity per component;
        // without the class-level @BatchSize on ToolEntity that is a per-tool SELECT
        // (N+1). The tool and the non-cascaded junction are persisted on their own.
        val toolName = "qc-tool-$index"
        toolRepository.save(ToolEntity(name = toolName))
        requiredToolRepository.save(
            ComponentRequiredToolEntity(
                componentConfigurationId = saved.configurations.first().id!!,
                toolName = toolName,
            ),
        )
        return saved
    }

    /**
     * Persist [count] components. Index 0 is a parent; every other component points
     * its `parentComponent` at it, so the read paths' `parentComponent` access is
     * exercised for a count that scales with the fixture — this guards the to-one
     * batch loading (class-level `@BatchSize` on the target entity), which a
     * field-level annotation would NOT provide.
     */
    private fun persistComponents(count: Int) {
        var parent: ComponentEntity? = null
        repeat(count) { index ->
            val saved = persistComponent(index, parent)
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
    @DisplayName("find-by-artifacts statement count is independent of component count (no N+1)")
    fun findByArtifactsHasNoNPlusOne() {
        val probe = setOf(ArtifactDependency("com.example.probe", "anything", "1.5.0"))

        persistComponents(SMALL)
        val small = measure { resolver.findComponentsByArtifact(probe) }

        cleanDatabase()
        persistComponents(LARGE)
        val large = measure { resolver.findComponentsByArtifact(probe) }

        assertEquals(
            small,
            large,
            "find-by-artifacts must issue the same number of SQL statements for $SMALL and $LARGE " +
                "components (a difference means a per-component N+1 was reintroduced)",
        )
        assertTrue(
            large <= ARTIFACT_STATEMENT_BOUND,
            "find-by-artifacts issued $large statements, expected <= $ARTIFACT_STATEMENT_BOUND",
        )
    }

    @Test
    @DisplayName("find-by-docker-images statement count is independent of component / matched-image count")
    fun findByDockerImagesHasNoNPlusOne() {
        persistComponents(SMALL)
        val smallImages = (0 until SMALL).map { Image("qc-image-$it", IMAGE_TAG) }.toSet()
        val small = measure { resolver.findComponentsByDockerImages(smallImages) }

        cleanDatabase()
        persistComponents(LARGE)
        val largeImages = (0 until LARGE).map { Image("qc-image-$it", IMAGE_TAG) }.toSet()
        val large = measure { resolver.findComponentsByDockerImages(largeImages) }

        // Every image must actually resolve — otherwise findConfigurationByDockerImage
        // short-circuits at the NotFound catch and never reaches getResolvedComponentDefinition,
        // leaving the entity-threaded reload path (Part B) unmeasured. IMAGE_TAG is a fixed point
        // of the default `$major.$minor` version format, so calculateDistribution rewrites the
        // docker entry "qc-image-N" → "qc-image-N:$IMAGE_TAG", matching the requested image.
        assertEquals(
            LARGE,
            resolver.findComponentsByDockerImages(largeImages).size,
            "every requested docker image must resolve (so the resolve path is actually exercised)",
        )
        assertEquals(
            small,
            large,
            "find-by-docker-images must issue the same number of SQL statements for $SMALL and $LARGE " +
                "matched images (a difference means the per-image component reload / lazy N+1 was reintroduced)",
        )
        assertTrue(
            large <= DOCKER_STATEMENT_BOUND,
            "find-by-docker-images issued $large statements, expected <= $DOCKER_STATEMENT_BOUND",
        )
    }

    companion object {
        // Both deliberately below @BatchSize(100) so each role loads in one IN batch.
        private const val SMALL = 2
        private const val LARGE = 8

        // Version range + tag chosen so IMAGE_TAG is a fixed point of the default
        // `$major.$minor` Jira release format (normalize("1.2") == "1.2"), in range — so the
        // docker entry resolves to "qc-image-N:1.2" and matches the requested image exactly.
        private const val VERSION_RANGE = "[1.0,2.0)"
        private const val IMAGE_TAG = "1.2"

        // Secondary backstops (the cross-size equality above is the primary guard).
        // Both read paths drive `toEscrowModule`, which walks every child role (incl. the
        // required-tool junction + tool to-one), so the post-fix constant is "findAll + one
        // batched IN select per role". Calibrated from the observed post-fix baseline with
        // headroom for Hibernate batch-loader drift.
        private const val ARTIFACT_STATEMENT_BOUND = 25L
        private const val DOCKER_STATEMENT_BOUND = 35L

        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            // Enable Hibernate statistics only for this test's application context.
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { "true" }
        }
    }
}
