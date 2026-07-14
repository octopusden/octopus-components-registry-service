package org.octopusden.octopus.components.registry.server.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * MIG-047 P1-A: schema CHECK constraint must accept the `group-artifact-pattern`
 * marker name introduced by the MIG-047 import path.
 *
 * Background:
 *   `ImportServiceImpl.emitMarkerOverrides` (post-MIG-047 fix) persists a
 *   `ComponentConfigurationEntity` with `rowType = "MARKER"` and
 *   `overriddenAttribute = "group-artifact-pattern"` whenever the DSL sets
 *   `groupId`/`artifactId` per range without an explicit `distribution { gav = … }`
 *   block. The unit tests for that path use a mocked repository, so they never
 *   hit the actual DB CHECK constraint defined in `V1__schema.sql` on the
 *   `component_configurations.overridden_attribute` column.
 *
 * Pre-fix behaviour (RED):
 *   The CHECK constraint's MARKER allowlist enumerates only the seven names in
 *   `MarkerAttributes.ALL` (`vcs.settings`, `distribution.*`, `build.*`).
 *   Inserting `overridden_attribute = 'group-artifact-pattern'` violates the
 *   constraint and the transaction rolls back with `SQLState 23514`
 *   (`check_violation`).
 *
 * Post-fix behaviour (GREEN):
 *   The CHECK allowlist accepts `'group-artifact-pattern'` for MARKER rows and
 *   the symmetric NOT-IN list excludes it from SCALAR_OVERRIDE rows. The save
 *   succeeds and the row reloads with the expected child rows.
 *
 * Test environment uses a PostgreSQL 16 testcontainer with `ddl-auto=validate`
 * + Flyway applying the production V1__schema.sql, mirroring the
 * `FlywayValidatePostgresStartupTest` SYS-026 pattern.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db-validate")
@Timeout(120)
@Tag("integration")
class MIG047SchemaConstraintIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

    @Autowired
    private lateinit var mavenArtifactRepository: DistributionMavenArtifactRepository

    init {
        val testResourcesPath =
            Paths
                .get(
                    MIG047SchemaConstraintIntegrationTest::class.java.getResource("/expected-data")!!.toURI(),
                ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName(
        "MIG-047 P1-A: MARKER row with overridden_attribute='group-artifact-pattern' " +
            "persists against the production Flyway schema",
    )
    @Transactional
    fun mig047_p1a_groupArtifactPatternMarkerPersists() {
        val component =
            componentRepository.save(
                ComponentEntity(
                    componentKey = "MIG047-P1A-FIXTURE",
                    archived = false,
                ),
            )

        // A BASE row is required because the override range's MARKER row references
        // the same (component, version_range) tuple where typed scalars live.
        // `build_system` is set to satisfy the UI-swift-sloth CHECK
        // (`row_type <> 'BASE' OR build_system IS NOT NULL`) — the legacy
        // pre-strict shape persisted BASE rows with all scalars NULL.
        configurationRepository.save(
            ComponentConfigurationEntity(
                component = component,
                versionRange = "(,0),[0,)",
                rowType = "BASE",
                buildSystem = "MAVEN",
            ),
        )

        val markerRow =
            ComponentConfigurationEntity(
                component = component,
                versionRange = "[2.0,)",
                overriddenAttribute = MarkerAttributes.GROUP_ARTIFACT_PATTERN,
                rowType = "MARKER",
            )
        val savedMarker = configurationRepository.save(markerRow)

        mavenArtifactRepository.save(
            DistributionMavenArtifactEntity(
                componentConfiguration = savedMarker,
                groupPattern = "com.example.fixture",
                artifactPattern = "alpha-fixture",
                extension = null,
                classifier = null,
                sortOrder = 0,
            ),
        )

        val reloaded = configurationRepository.findById(savedMarker.id!!).orElse(null)
        assertNotNull(reloaded, "MARKER row with GROUP_ARTIFACT_PATTERN must persist")
        assertEquals("MARKER", reloaded!!.rowType)
        assertEquals(
            MarkerAttributes.GROUP_ARTIFACT_PATTERN,
            reloaded.overriddenAttribute,
            "overridden_attribute must round-trip exactly as 'group-artifact-pattern'",
        )

        val children = mavenArtifactRepository.findByComponentConfigurationId(savedMarker.id!!)
        assertEquals(1, children.size, "Child maven-artifact row must persist alongside the marker")
        assertEquals("com.example.fixture", children[0].groupPattern)
        assertEquals("alpha-fixture", children[0].artifactPattern)
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
