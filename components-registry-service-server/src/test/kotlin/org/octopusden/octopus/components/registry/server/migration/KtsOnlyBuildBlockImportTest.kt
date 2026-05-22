package org.octopusden.octopus.components.registry.server.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
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
 * IMP-003: Regression guard for the KTS-only-build-block component shape.
 *
 * The scenario: KTS carries `build { tools { database { oracle {...} } } }` but
 * the Groovy counterpart has NO `build` block at all. Without `build { }` in
 * Defaults.groovy, each per-component EscrowModuleConfig gets
 * buildConfiguration = null after merging. EscrowConfigurationLoader.mergeComponents
 * then NPEs on `buildConfiguration.buildTools.addAll(...)`, the component is never
 * persisted, and `attachBuildToolBeans` never fires.
 *
 * Uses its own isolated fixture directory (components-registry/imp003) so that
 * global TestComponents.groovy and Defaults.groovy are NOT touched.
 * The imp003 profile overrides work-dir; common+ft-db supply shared settings
 * (supportedSystems, product-type map, H2 datasource, auto-migrate).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "imp003", "ft-db")
@Timeout(120)
class KtsOnlyBuildBlockImportTest {

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
            Paths.get(
                KtsOnlyBuildBlockImportTest::class.java.getResource("/expected-data")!!.toURI(),
            ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    // -------------------------------------------------------------------------
    // IMP-003: KTS-only build block persists oracle bean via auto-migrate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "IMP-003: TEST_BUILD_KTS_ONLY (KTS has oracle build tool, Groovy has no build block) " +
            "MUST persist its oracle bean after auto-migrate",
    )
    @Transactional
    fun imp003_ktsOnlyBuildBlock_persistsOracleBean() {
        val component = componentRepository.findByComponentKey("TEST_BUILD_KTS_ONLY")
        assertNotNull(component, "TEST_BUILD_KTS_ONLY must be migrated — if null, mergeComponents NPEd")

        val baseRow = configurationRepository.findBaseByComponentId(component!!.id!!)
        assertNotNull(baseRow, "BASE row must exist for TEST_BUILD_KTS_ONLY")

        val beans = buildToolBeanRepository
            .findByComponentConfigurationId(baseRow!!.id!!)
            .sortedBy { it.sortOrder }

        assertEquals(
            1,
            beans.size,
            "Expected 1 build-tool bean (oracleDatabase 12.0); got ${beans.size}. " +
                "If this is 0, Defaults.groovy is missing `build { }` so buildConfiguration " +
                "is null when mergeComponents runs and attachBuildToolBeans never fires.",
        )

        val oracle = beans.firstOrNull { it.beanType == "oracleDatabase" }
        assertNotNull(oracle, "oracleDatabase bean must be persisted")
        assertEquals("12.0", oracle!!.versionPattern)
    }
}
