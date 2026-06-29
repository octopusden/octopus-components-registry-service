package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.AfterEach
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
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * TD-010 regression guard (DB-backed, schema-v2).
 *
 * Proves the range-VIEW enumeration applies a broad scalar override to a strictly contained narrow
 * enumeration range. The component declares:
 *   - BASE on ALL_VERSIONS with buildFilePath = "BasePath";
 *   - a RANGE_PRESENCE row on the narrow [1.0,2.0) (so that range is enumerated as its own view);
 *   - a broad SCALAR_OVERRIDE on [1.0,3.0) setting buildFilePath = "BroadOverridePath".
 *
 * The narrow [1.0,2.0) view is strictly contained in the broad [1.0,3.0) override, so the override
 * must win on that view. Before the fix, rangeApplies used string equality ("[1.0,3.0)" !=
 * "[1.0,2.0)"), so the broad override was silently dropped and the [1.0,2.0) view fell back to the
 * base "BasePath" — the exact symptom TD-010 describes. The fix's containment heuristic projects the
 * override onto the contained view.
 *
 * Scaffold mirrors TypeConfigurationArrayLeakReproTest: SpringBootTest + test-db profile + a
 * Postgres testcontainer, seeding entities directly through the repository.
 */
@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
@Timeout(120)
@Tag("integration")
class RangeViewBroadOverrideContainmentIntegrationTest {

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    @Qualifier("databaseComponentRegistryResolver")
    private lateinit var resolver: ComponentRegistryResolver

    init {
        // application-common.yml binds components-registry.work-dir from this env-backed property.
        val testResourcesPath =
            Paths.get(
                RangeViewBroadOverrideContainmentIntegrationTest::class.java
                    .getResource("/expected-data")!!.toURI(),
            ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @AfterEach
    fun cleanDatabase() {
        componentRepository.deleteAll()
    }

    @Test
    @DisplayName("TD-010: broad scalar override [1.0,3.0) is applied to the contained narrow view [1.0,2.0)")
    fun broadOverrideAppliesToContainedNarrowEnumerationRange() {
        val key = "td010-containment-comp"
        val component = ComponentEntity(componentKey = key, archived = false)

        val base = ComponentConfigurationEntity(
            component = component,
            versionRange = ALL_VERSIONS,
            overriddenAttribute = null,
            rowType = "BASE",
            buildSystem = "MAVEN",
            buildFilePath = "BasePath",
            deprecated = false,
        )
        // Narrow presence row: makes [1.0,2.0) a distinct enumerated view (carries no scalars itself).
        val narrowPresence = ComponentConfigurationEntity(
            component = component,
            versionRange = "[1.0,2.0)",
            overriddenAttribute = null,
            rowType = "RANGE_PRESENCE",
        )
        // Broad scalar override strictly containing the narrow view.
        val broadOverride = ComponentConfigurationEntity(
            component = component,
            versionRange = "[1.0,3.0)",
            overriddenAttribute = "build.buildFilePath",
            rowType = "SCALAR_OVERRIDE",
            buildFilePath = "BroadOverridePath",
        )
        component.configurations.addAll(listOf(base, narrowPresence, broadOverride))
        // An ownership mapping keeps the component well-formed for the full toEscrowModule walk.
        component.addOwnershipMapping("com.example.td010", "widget-a")
        componentRepository.save(component)

        val module = resolver.getComponentById(key)
        assertNotNull(module, "component must resolve to an EscrowModule")

        val narrowView = module!!.moduleConfigurations.firstOrNull { it.versionRangeString == "[1.0,2.0)" }
        assertNotNull(
            narrowView,
            "the narrow [1.0,2.0) range must be enumerated as its own view; " +
                "got ranges=${module.moduleConfigurations.map { it.versionRangeString }}",
        )
        assertEquals(
            "BroadOverridePath",
            narrowView!!.buildFilePath,
            "the broad [1.0,3.0) override must be applied to the contained narrow [1.0,2.0) view " +
                "(TD-010 containment); base 'BasePath' here means the override was dropped",
        )

        // Sanity: the broad override's own [1.0,3.0) view also carries the override (equality path).
        val broadView = module.moduleConfigurations.firstOrNull { it.versionRangeString == "[1.0,3.0)" }
        assertNotNull(broadView, "the broad [1.0,3.0) override range must also be enumerated")
        assertEquals("BroadOverridePath", broadView!!.buildFilePath)
    }

    @Test
    @DisplayName("TD-010 open-ended: broad open-upper override [1.0,) is applied to the contained open-upper view [2.0,)")
    fun openUpperOverrideAppliesToContainedOpenUpperView() {
        // The motivating "default from this version onward" case: a broad open-upper override must
        // project onto a narrower open-upper enumeration view it contains. Before open-ended support,
        // parseSingleInterval rejected "[2.0,)" so rangeApplies returned false and the [2.0,) view fell
        // back to base "BasePath". With sentinel-tail sampling, [2.0,) ⊆ [1.0,) holds and the override
        // wins on that view.
        val key = "td010-openupper-comp"
        val component = ComponentEntity(componentKey = key, archived = false)

        val base = ComponentConfigurationEntity(
            component = component,
            versionRange = ALL_VERSIONS,
            overriddenAttribute = null,
            rowType = "BASE",
            buildSystem = "MAVEN",
            buildFilePath = "BasePath",
            deprecated = false,
        )
        // Open-upper presence row: makes [2.0,) a distinct enumerated view.
        val openUpperPresence = ComponentConfigurationEntity(
            component = component,
            versionRange = "[2.0,)",
            overriddenAttribute = null,
            rowType = "RANGE_PRESENCE",
        )
        // Broad open-upper override strictly containing the [2.0,) view.
        val broadOpenUpperOverride = ComponentConfigurationEntity(
            component = component,
            versionRange = "[1.0,)",
            overriddenAttribute = "build.buildFilePath",
            rowType = "SCALAR_OVERRIDE",
            buildFilePath = "FuturePath",
        )
        component.configurations.addAll(listOf(base, openUpperPresence, broadOpenUpperOverride))
        component.addOwnershipMapping("com.example.td010", "widget-a")
        componentRepository.save(component)

        val module = resolver.getComponentById(key)
        assertNotNull(module, "component must resolve to an EscrowModule")

        val openUpperView = module!!.moduleConfigurations.firstOrNull { it.versionRangeString == "[2.0,)" }
        assertNotNull(
            openUpperView,
            "the open-upper [2.0,) range must be enumerated as its own view; " +
                "got ranges=${module.moduleConfigurations.map { it.versionRangeString }}",
        )
        assertEquals(
            "FuturePath",
            openUpperView!!.buildFilePath,
            "the broad open-upper [1.0,) override must be applied to the contained [2.0,) view " +
                "(TD-010 open-ended containment); base 'BasePath' here means the override was dropped",
        )
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
