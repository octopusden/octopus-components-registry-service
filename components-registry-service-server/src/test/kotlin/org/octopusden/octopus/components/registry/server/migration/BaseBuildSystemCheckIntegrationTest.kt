package org.octopusden.octopus.components.registry.server.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths
import java.sql.SQLException

/**
 * DB-level guard for the UI-swift-sloth strict-contract CHECK:
 *
 *   `CHECK (row_type <> 'BASE' OR build_system IS NOT NULL)`
 *
 * The service layer (`ComponentManagementServiceImpl.createComponent`) is the
 * user-visible 400 path that rejects payloads missing
 * `baseConfiguration.build.buildSystem`. This test exercises the second line
 * of defence: a direct repository-level INSERT of a BASE row with
 * `build_system = NULL` MUST be rejected by Postgres so a future bulk-loader,
 * import path, or service-layer regression cannot quietly persist an
 * under-specified BASE row.
 *
 * MARKER and RANGE_PRESENCE rows continue to require ALL typed scalars
 * (including `build_system`) to be NULL — the consolidated upstream CHECK
 * has not changed. This file pins both the positive and the negative
 * branches against the production Flyway schema.
 *
 * Uses PostgreSQL 16 testcontainer + Flyway-applied V1__schema.sql, mirroring
 * `MIG047SchemaConstraintIntegrationTest`. The CHECK lives inline in V1 (not
 * a separate V2 migration): per `project_db_fresh_on_deploy.md`, every CRS
 * environment recreates the DB from scratch on deploy, so V1 byte-stability
 * buys nothing in the pre-prod window.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db-validate")
@Timeout(120)
class BaseBuildSystemCheckIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

    init {
        val testResourcesPath =
            Paths.get(
                BaseBuildSystemCheckIntegrationTest::class.java.getResource("/expected-data")!!.toURI(),
            ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("BASE row with build_system = NULL is rejected by the DB CHECK")
    fun base_row_with_null_buildSystem_is_rejected() {
        val component =
            componentRepository.save(
                ComponentEntity(componentKey = "STRICT-BASE-NULL-BS", archived = false),
            )
        val violating =
            ComponentConfigurationEntity(
                component = component,
                versionRange = "(,0),[0,)",
                rowType = "BASE",
                buildSystem = null,
            )
        val ex =
            runCatching { configurationRepository.saveAndFlush(violating) }.exceptionOrNull()
        assertNotNull(ex, "expected a constraint-violation exception; got success")
        // Walk the exception cause chain to the root JDBC `SQLException` and
        // assert specifically on SQLState `23514` (`check_violation`) — NOT
        // on the broader `DataIntegrityViolationException` class, which also
        // covers FK / unique-index violations and would let a future
        // regression-by-different-constraint pass the assertion. Mirrors the
        // SQLState-specific check pattern used by `MIG047SchemaConstraintIntegrationTest`.
        val sqlException =
            generateSequence(ex) { it.cause }.firstOrNull { it is SQLException } as? SQLException
        assertNotNull(sqlException, "expected a SQLException in the cause chain; got $ex")
        assertEquals(
            "23514",
            sqlException!!.sqlState,
            "expected SQLState 23514 (check_violation) for the BASE+null-buildSystem CHECK; " +
                "got SQLState='${sqlException.sqlState}', message='${sqlException.message}'",
        )
    }

    @Test
    @DisplayName("BASE row with build_system non-null persists against the production schema")
    fun base_row_with_nonNull_buildSystem_persists() {
        val component =
            componentRepository.save(
                ComponentEntity(componentKey = "STRICT-BASE-OK-BS", archived = false),
            )
        val ok =
            configurationRepository.saveAndFlush(
                ComponentConfigurationEntity(
                    component = component,
                    versionRange = "(,0),[0,)",
                    rowType = "BASE",
                    buildSystem = "MAVEN",
                ),
            )
        assertNotNull(ok.id, "BASE row with build_system='MAVEN' must persist")
    }

    @Test
    @DisplayName("MARKER row with build_system = NULL continues to persist (unchanged by the new CHECK)")
    fun marker_row_with_null_buildSystem_still_persists() {
        // Cross-check: the new CHECK is narrowly targeted at BASE rows. MARKER
        // rows (where all typed scalars MUST be NULL per the consolidated
        // upstream CHECK) continue to persist with build_system = NULL.
        val component =
            componentRepository.save(
                ComponentEntity(componentKey = "STRICT-MARKER-OK-NULL-BS", archived = false),
            )
        configurationRepository.saveAndFlush(
            ComponentConfigurationEntity(
                component = component,
                versionRange = "(,0),[0,)",
                rowType = "BASE",
                buildSystem = "MAVEN",
            ),
        )
        val saved =
            configurationRepository.saveAndFlush(
                ComponentConfigurationEntity(
                    component = component,
                    versionRange = "[2.0,)",
                    rowType = "MARKER",
                    overriddenAttribute = MarkerAttributes.GROUP_ARTIFACT_PATTERN,
                    buildSystem = null,
                ),
            )
        assertNotNull(saved.id, "MARKER row with build_system=null must persist")
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
