package org.octopusden.octopus.components.registry.server.migration

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionGenericArtifactEntity
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * SYS-094 V8 taxonomy constraint: verifies that the PostgreSQL CHECK constraint
 * added by V8__add_distribution_generic_artifacts.sql correctly allows MARKER and
 * rejects SCALAR_OVERRIDE for `overridden_attribute = 'distribution.generic'`.
 *
 * Uses a real PostgreSQL 16 testcontainer with Flyway applying all migrations
 * (`ddl-auto=validate`), mirroring the production schema.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db-validate")
@Timeout(120)
@Tag("integration")
class V8GenericArtifactConstraintIntegrationTest {

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

    @PersistenceContext
    private lateinit var em: EntityManager

    init {
        val testResourcesPath =
            Paths.get(
                V8GenericArtifactConstraintIntegrationTest::class.java.getResource("/expected-data")!!.toURI(),
            ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    // -------------------------------------------------------------------------
    // SYS-094-V8-001: MARKER / distribution.generic persists with artifact child
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "SYS-094-V8-001: MARKER row with overridden_attribute='distribution.generic' and " +
            "its generic artifact child persist and reload correctly after EntityManager clear",
    )
    @Transactional
    fun `SYS-094-V8-001 distribution generic MARKER row persists with child`() {
        val component = componentRepository.save(
            ComponentEntity(componentKey = "SYS094-V8-001", archived = false),
        )

        configurationRepository.save(
            ComponentConfigurationEntity(
                component = component,
                versionRange = "(,0),[0,)",
                rowType = "BASE",
                buildSystem = "MAVEN",
            ),
        )

        val markerRow = ComponentConfigurationEntity(
            component = component,
            versionRange = "[2.0,)",
            rowType = "MARKER",
            overriddenAttribute = MarkerAttributes.DISTRIBUTION_GENERIC,
        )
        markerRow.genericArtifacts.add(
            DistributionGenericArtifactEntity(
                componentConfiguration = markerRow,
                path = "releases/sys094-fixture/\${version}/sys094-fixture.tar.gz",
                sortOrder = 0,
            ),
        )
        val savedMarker = configurationRepository.save(markerRow)

        em.flush()
        em.clear()

        val reloaded = configurationRepository.findById(savedMarker.id!!).orElse(null)
        assertNotNull(reloaded, "MARKER row must persist on PostgreSQL + Flyway schema")
        assertEquals("MARKER", reloaded!!.rowType)
        assertEquals(MarkerAttributes.DISTRIBUTION_GENERIC, reloaded.overriddenAttribute)

        val children = reloaded.genericArtifacts
        assertEquals(1, children.size, "Child generic artifact must persist alongside the marker")
        assertEquals(
            "releases/sys094-fixture/\${version}/sys094-fixture.tar.gz",
            children[0].path,
        )
    }

    // -------------------------------------------------------------------------
    // SYS-094-V8-002: SCALAR_OVERRIDE / distribution.generic is rejected
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "SYS-094-V8-002: SCALAR_OVERRIDE row with overridden_attribute='distribution.generic' " +
            "violates the V8 taxonomy CHECK constraint",
    )
    @Transactional
    fun `SYS-094-V8-002 distribution generic SCALAR_OVERRIDE rejected by CHECK constraint`() {
        val component = componentRepository.save(
            ComponentEntity(componentKey = "SYS094-V8-002", archived = false),
        )

        configurationRepository.save(
            ComponentConfigurationEntity(
                component = component,
                versionRange = "(,0),[0,)",
                rowType = "BASE",
                buildSystem = "MAVEN",
            ),
        )

        val scalarRow = ComponentConfigurationEntity(
            component = component,
            versionRange = "[2.0,)",
            rowType = "SCALAR_OVERRIDE",
            overriddenAttribute = MarkerAttributes.DISTRIBUTION_GENERIC,
        )

        assertThrows<DataIntegrityViolationException> {
            configurationRepository.save(scalarRow)
            em.flush()
        }
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
