package org.octopusden.octopus.components.registry.server.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.mapper.toBuildToolBean
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Paths

/**
 * IMP-001/IMP-002: Verifies that the import pipeline persists build-tool beans
 * (OracleDatabaseToolBean, PTKProductToolBean) from the KTS DSL fixture into
 * `component_build_tool_beans` rows and that `toBuildToolBean()` round-trips
 * them back to the correct domain objects.
 *
 * Uses the ft-db profile (H2 in-memory, `ddl-auto: create-drop`, `auto-migrate: true`).
 * The fixture component `TEST_COMPONENT_BUILD_TOOLS` is defined in
 * `test-common/src/test/resources/components-registry/common/TestComponents.kts`:
 *
 * ```kotlin
 * component("TEST_COMPONENT_BUILD_TOOLS") {
 *     build {
 *         tools {
 *             database { oracle { version = "11.2" } }
 *             product { type("PT_K") { version = "03.49" } }
 *         }
 *     }
 * }
 * ```
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@Timeout(120)
@Tag("integration")
class BuildToolBeansImportTest {
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
                    BuildToolBeansImportTest::class.java.getResource("/expected-data")!!.toURI(),
                ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    // -------------------------------------------------------------------------
    // IMP-001: auto-migrate persists build-tool beans in component_build_tool_beans
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "IMP-001: after auto-migrate, TEST_COMPONENT_BUILD_TOOLS base row has " +
            "oracleDatabase(11.2) and kProduct(03.49) in component_build_tool_beans",
    )
    @Transactional
    fun imp001_autoMigrate_persistsBuildToolBeans() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT_BUILD_TOOLS")
        assertNotNull(component, "TEST_COMPONENT_BUILD_TOOLS must be migrated")

        val baseRow = configurationRepository.findBaseByComponentId(component!!.id!!)
        assertNotNull(baseRow, "BASE row must exist for TEST_COMPONENT_BUILD_TOOLS")

        val beans = buildToolBeanRepository
            .findByComponentConfigurationId(baseRow!!.id!!)
            .sortedBy { it.sortOrder }

        assertEquals(2, beans.size, "Expected 2 build-tool bean rows (oracle + kProduct)")

        val oracle = beans.firstOrNull { it.beanType == "oracleDatabase" }
        assertNotNull(oracle, "oracleDatabase bean must be persisted")
        assertEquals("11.2", oracle!!.versionPattern)

        val kProduct = beans.firstOrNull { it.beanType == "kProduct" }
        assertNotNull(kProduct, "kProduct bean must be persisted")
        assertEquals("03.49", kProduct!!.versionPattern)
    }

    // -------------------------------------------------------------------------
    // IMP-002: toBuildToolBean() round-trip produces expected BuildTool instances
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "IMP-002: toBuildToolBean() round-trip — persisted oracle row → OracleDatabaseToolBean(11.2); " +
            "kProduct row → PTKProductToolBean(03.49)",
    )
    @Transactional
    fun imp002_toBuildToolBean_roundTrip() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT_BUILD_TOOLS")
        assertNotNull(component, "TEST_COMPONENT_BUILD_TOOLS must be migrated")

        val baseRow = configurationRepository.findBaseByComponentId(component!!.id!!)
        assertNotNull(baseRow, "BASE row must exist")

        val beans = buildToolBeanRepository
            .findByComponentConfigurationId(baseRow!!.id!!)
            .sortedBy { it.sortOrder }

        val buildTools = beans.mapNotNull { it.toBuildToolBean() }

        val expectedOracle = OracleDatabaseToolBean()
        expectedOracle.setVersion("11.2")
        val expectedPtk = PTKProductToolBean()
        expectedPtk.setVersion("03.49")

        assert(buildTools.contains(expectedOracle)) {
            "Expected OracleDatabaseToolBean(11.2) in $buildTools"
        }
        assert(buildTools.contains(expectedPtk)) {
            "Expected PTKProductToolBean(03.49) in $buildTools"
        }
    }
}
