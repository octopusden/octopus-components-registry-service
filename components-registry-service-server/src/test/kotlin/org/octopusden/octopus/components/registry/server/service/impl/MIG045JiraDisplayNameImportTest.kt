package org.octopusden.octopus.components.registry.server.service.impl

import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.DefaultConfigParameters
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.ComponentVersionFormat

/**
 * MIG-045 import-path tests: `populateScalarsFromConfig` and `emitScalarOverrides`
 * must persist per-range `jira.displayName`, including explicit null-clear.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG045JiraDisplayNameImportTest {

    private lateinit var service: ImportServiceImpl
    private lateinit var configurationRepository: ComponentConfigurationRepository
    private lateinit var populateScalarsFromConfigMethod: Method
    private lateinit var emitScalarOverridesMethod: Method

    @BeforeEach
    fun setUp() {
        val configurationLoader = mock(EscrowConfigurationLoader::class.java)
        val emptyDefaults = mock(DefaultConfigParameters::class.java)
        doReturn(emptyDefaults).`when`(configurationLoader).loadCommonDefaults(emptyMap())

        configurationRepository = mock(ComponentConfigurationRepository::class.java)
        `when`(configurationRepository.save(any(ComponentConfigurationEntity::class.java)))
            .thenAnswer { invocation -> invocation.arguments[0] }

        service = ImportServiceImpl(
            gitResolver = mock(ComponentRegistryResolverImpl::class.java),
            dbResolver = mock(DatabaseComponentRegistryResolver::class.java),
            componentSourceRepository = mock(ComponentSourceRepository::class.java),
            sourceRegistry = mock(ComponentSourceRegistry::class.java),
            configurationLoader = configurationLoader,
            registryConfigRepository = mock(RegistryConfigRepository::class.java),
            componentRepository = mock(ComponentRepository::class.java),
            configurationRepository = configurationRepository,
            componentGroupRepository = mock(ComponentGroupRepository::class.java),
            systemRepository = mock(SystemRepository::class.java),
            toolRepository = mock(ToolRepository::class.java),
            labelRepository = mock(LabelRepository::class.java),
            componentLabelRepository = mock(ComponentLabelRepository::class.java),
            componentRequiredToolRepository = mock(ComponentRequiredToolRepository::class.java),
            componentBuildToolBeanRepository = mock(ComponentBuildToolBeanRepository::class.java),
        )

        populateScalarsFromConfigMethod = ImportServiceImpl::class.java.getDeclaredMethod(
            "populateScalarsFromConfig",
            ComponentConfigurationEntity::class.java,
            EscrowModuleConfig::class.java,
        )
        populateScalarsFromConfigMethod.isAccessible = true

        emitScalarOverridesMethod = ImportServiceImpl::class.java.getDeclaredMethod(
            "emitScalarOverrides",
            ComponentEntity::class.java,
            EscrowModuleConfig::class.java,
            EscrowModuleConfig::class.java,
        )
        emitScalarOverridesMethod.isAccessible = true
    }

    private fun setGroovyField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun makeConfig(
        versionRange: String?,
        displayName: String?,
        projectKey: String = "PRDN",
    ): EscrowModuleConfig {
        val cfg = EscrowModuleConfig()
        if (versionRange != null) {
            setGroovyField(cfg, "versionRange", versionRange)
        }
        val jira =
            JiraComponent(
                projectKey,
                displayName,
                ComponentVersionFormat.create("\$major", "\$major.\$minor"),
                null,
                false,
                false,
            )
        setGroovyField(cfg, "jiraConfiguration", jira)
        return cfg
    }

    private fun callPopulateScalarsFromConfig(
        row: ComponentConfigurationEntity,
        cfg: EscrowModuleConfig,
    ) {
        populateScalarsFromConfigMethod.invoke(service, row, cfg)
    }

    @Suppress("UNCHECKED_CAST")
    private fun callEmitScalarOverrides(
        component: ComponentEntity,
        base: EscrowModuleConfig,
        override: EscrowModuleConfig,
    ): List<ComponentConfigurationEntity> =
        emitScalarOverridesMethod.invoke(service, component, base, override)
            as List<ComponentConfigurationEntity>

    @Test
    @DisplayName("MIG-045-004: populateScalarsFromConfig clears stale jiraDisplayName when DSL declares displayName null")
    fun `MIG-045-004 populateScalarsFromConfig writes null jira displayName from jira block`() {
        val row =
            ComponentConfigurationEntity(
                component = ComponentEntity(componentKey = "fixture"),
                versionRange = "[1.0,2.0)",
                rowType = "BASE",
            )
        row.jiraDisplayName = "stale inherited value"

        callPopulateScalarsFromConfig(row, makeConfig("[1.0,2.0)", displayName = null))

        assertNull(
            row.jiraDisplayName,
            "explicit jira.displayName=null in DSL must overwrite a stale row value",
        )
    }

    @Test
    @DisplayName("MIG-045-005: emitScalarOverrides emits jira.displayName when override range differs from base")
    fun `MIG-045-005 emitScalarOverrides emits jira displayName scalar override row`() {
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "display-fixture")

        val baseConfig = makeConfig("[1.0,2.0)", displayName = null)
        val overrideConfig = makeConfig("[2.0,)", displayName = "Range Override Display")

        val rows = callEmitScalarOverrides(component, baseConfig, overrideConfig)

        val displayNameRow = rows.single { it.overriddenAttribute == "jira.displayName" }
        assertEquals("[2.0,)", displayNameRow.versionRange)
        assertEquals("Range Override Display", displayNameRow.jiraDisplayName)
        assertTrue(rows.none { it.versionRange == "[1.0,2.0)" && it.overriddenAttribute == "jira.displayName" })
    }
}
