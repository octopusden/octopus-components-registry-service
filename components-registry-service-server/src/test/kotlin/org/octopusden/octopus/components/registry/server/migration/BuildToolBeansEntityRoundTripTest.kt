package org.octopusden.octopus.components.registry.server.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Paths

/**
 * MIG-042: JPA round-trip for `component_build_tool_beans` table.
 *
 * Verifies that all 6 bean types + Oracle with/without edition can be
 * persisted and reloaded via JPA without data loss. Uses H2 in-memory DB
 * via the `ft-db` profile (Hibernate create-drop, Flyway disabled).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
@Tag("integration")
class BuildToolBeansEntityRoundTripTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

    @Autowired
    private lateinit var buildToolBeanRepository: ComponentBuildToolBeanRepository

    init {
        val testResourcesPath =
            Paths
                .get(
                    BuildToolBeansEntityRoundTripTest::class.java.getResource("/expected-data")!!.toURI(),
                ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun createComponent(key: String): ComponentEntity {
        val existing = componentRepository.findByComponentKey(key)
        if (existing != null) return existing
        return componentRepository.save(
            ComponentEntity(
                componentKey = key,
                archived = false,
            ),
        )
    }

    private fun createBaseRow(component: ComponentEntity): ComponentConfigurationEntity {
        val existing = configurationRepository.findBaseByComponentId(component.id!!)
        if (existing != null) return existing
        return configurationRepository.save(
            ComponentConfigurationEntity(
                component = component,
                versionRange = "(,0),[0,)",
                rowType = "BASE",
                // UI-swift-sloth DB CHECK requires BASE rows to set buildSystem.
                buildSystem = "MAVEN",
            ),
        )
    }

    @Test
    @DisplayName("MIG-042: all 6 bean types persist and reload correctly")
    @Transactional
    fun mig042_allSixBeanTypesPersistAndReload() {
        val component = createComponent("BTB-ROUND-TRIP-TEST")
        val baseRow = createBaseRow(component)

        val beans = listOf(
            ComponentBuildToolBeanEntity(
                componentConfiguration = baseRow,
                beanType = "oracleDatabase",
                toolType = "ORACLE",
                settingsProperty = "db",
                versionPattern = "[12,)",
                edition = null,
                sortOrder = 0,
            ),
            ComponentBuildToolBeanEntity(
                componentConfiguration = baseRow,
                beanType = "oracleDatabase",
                toolType = "ORACLE",
                settingsProperty = "db",
                versionPattern = "[19,)",
                edition = "ENTERPRISE",
                sortOrder = 1,
            ),
            ComponentBuildToolBeanEntity(
                componentConfiguration = baseRow,
                beanType = "cProduct",
                toolType = null,
                settingsProperty = "uscschema",
                versionPattern = "[1,)",
                edition = null,
                sortOrder = 2,
            ),
            ComponentBuildToolBeanEntity(
                componentConfiguration = baseRow,
                beanType = "kProduct",
                toolType = null,
                settingsProperty = "uskschema",
                versionPattern = null,
                edition = null,
                sortOrder = 3,
            ),
            ComponentBuildToolBeanEntity(
                componentConfiguration = baseRow,
                beanType = "dProduct",
                toolType = null,
                settingsProperty = "usdschema",
                versionPattern = null,
                edition = null,
                sortOrder = 4,
            ),
            ComponentBuildToolBeanEntity(
                componentConfiguration = baseRow,
                beanType = "dDbProduct",
                toolType = null,
                settingsProperty = "usdschema",
                versionPattern = null,
                edition = null,
                sortOrder = 5,
            ),
            ComponentBuildToolBeanEntity(
                componentConfiguration = baseRow,
                beanType = "odbc",
                toolType = null,
                settingsProperty = null,
                versionPattern = "12.2",
                edition = null,
                sortOrder = 6,
            ),
        )

        beans.forEach { buildToolBeanRepository.save(it) }

        val configId = baseRow.id!!
        val reloaded = buildToolBeanRepository
            .findByComponentConfigurationId(configId)
            .sortedBy { it.sortOrder }

        assertEquals(7, reloaded.size, "All 7 bean rows must be persisted")

        // Oracle without edition
        val oracle1 = reloaded[0]
        assertEquals("oracleDatabase", oracle1.beanType)
        assertEquals("ORACLE", oracle1.toolType)
        assertEquals("db", oracle1.settingsProperty)
        assertEquals("[12,)", oracle1.versionPattern)
        assertNull(oracle1.edition, "First Oracle row should have null edition")
        assertEquals(0, oracle1.sortOrder)
        assertNotNull(oracle1.id)

        // Oracle with edition
        val oracle2 = reloaded[1]
        assertEquals("oracleDatabase", oracle2.beanType)
        assertEquals("[19,)", oracle2.versionPattern)
        assertEquals("ENTERPRISE", oracle2.edition)
        assertEquals(1, oracle2.sortOrder)

        // cProduct
        val cProduct = reloaded[2]
        assertEquals("cProduct", cProduct.beanType)
        assertEquals("uscschema", cProduct.settingsProperty)
        assertNull(cProduct.toolType)
        assertEquals(2, cProduct.sortOrder)

        // kProduct
        assertEquals("kProduct", reloaded[3].beanType)

        // dProduct
        assertEquals("dProduct", reloaded[4].beanType)

        // dDbProduct
        assertEquals("dDbProduct", reloaded[5].beanType)

        // odbc
        val odbc = reloaded[6]
        assertEquals("odbc", odbc.beanType)
        assertEquals("12.2", odbc.versionPattern)
        assertNull(odbc.edition)
    }

    @Test
    @DisplayName("MIG-042: cascade delete removes build_tool_beans when configuration row is deleted via parent collection")
    @Transactional
    fun mig042_cascadeDeleteRemovesBeans() {
        val component = createComponent("BTB-CASCADE-DELETE-TEST")
        val baseRow = createBaseRow(component)

        val bean = ComponentBuildToolBeanEntity(
            componentConfiguration = baseRow,
            beanType = "oracleDatabase",
            toolType = "ORACLE",
            settingsProperty = "db",
            versionPattern = "[12,)",
            edition = null,
            sortOrder = 0,
        )
        // Add to the collection so JPA cascade applies
        baseRow.buildToolBeans.add(bean)
        configurationRepository.save(baseRow)
        configurationRepository.flush()

        val configId = baseRow.id!!
        assertEquals(1, buildToolBeanRepository.findByComponentConfigurationId(configId).size)

        // Remove via collection (orphanRemoval = true triggers delete)
        baseRow.buildToolBeans.clear()
        configurationRepository.save(baseRow)
        configurationRepository.flush()

        assertEquals(
            0,
            buildToolBeanRepository.findByComponentConfigurationId(configId).size,
            "Beans must be removed via orphanRemoval on the parent collection",
        )
    }
}
