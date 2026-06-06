package org.octopusden.octopus.components.registry.server.service.impl

import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
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
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.escrow.RepositoryType

/**
 * MIG-047 import-path: vcs.settings marker emission must pin vcs.externalRegistry.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG047VcsRegistryImportTest {

    private lateinit var service: ImportServiceImpl
    private lateinit var configurationRepository: ComponentConfigurationRepository
    private lateinit var emitMarkerOverridesMethod: Method

    @BeforeEach
    fun setUp() {
        val configurationLoader = mock(EscrowConfigurationLoader::class.java)
        val emptyDefaults = mock(DefaultConfigParameters::class.java)
        doReturn(emptyDefaults).`when`(configurationLoader).loadCommonDefaults(emptyMap())

        configurationRepository = mock(ComponentConfigurationRepository::class.java)
        `when`(configurationRepository.save(any(ComponentConfigurationEntity::class.java)))
            .thenAnswer { invocation ->
                val row = invocation.arguments[0] as ComponentConfigurationEntity
                if (row.id == null) {
                    row.id = UUID.randomUUID()
                }
                row
            }

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

        emitMarkerOverridesMethod = ImportServiceImpl::class.java.getDeclaredMethod(
            "emitMarkerOverrides",
            ComponentEntity::class.java,
            ComponentConfigurationEntity::class.java,
            EscrowModuleConfig::class.java,
            EscrowModuleConfig::class.java,
        )
        emitMarkerOverridesMethod.isAccessible = true
    }

    private fun setGroovyField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun makeConfig(
        versionRange: String,
        externalRegistry: String?,
        vcsPath: String?,
    ): EscrowModuleConfig {
        val cfg = EscrowModuleConfig()
        setGroovyField(cfg, "versionRange", versionRange)
        val roots =
            if (vcsPath == null) {
                emptyList()
            } else {
                listOf(
                    VersionControlSystemRoot.create(
                        "main",
                        RepositoryType.GIT,
                        vcsPath,
                        null,
                        null,
                        null,
                    ),
                )
            }
        setGroovyField(cfg, "vcsSettings", VCSSettings.create(externalRegistry, roots))
        return cfg
    }

    @Suppress("UNCHECKED_CAST")
    private fun callEmitMarkerOverrides(
        component: ComponentEntity,
        baseRow: ComponentConfigurationEntity,
        base: EscrowModuleConfig,
        override: EscrowModuleConfig,
    ): List<ComponentConfigurationEntity> =
        emitMarkerOverridesMethod.invoke(service, component, baseRow, base, override)
            as List<ComponentConfigurationEntity>

    @Test
    @DisplayName("MIG-047-004: emitMarkerOverrides pins vcs.externalRegistry when vcs.settings marker is emitted")
    fun `MIG-047-004 emitMarkerOverrides pins vcs externalRegistry when vcs settings marker is emitted`() {
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "vcs-import-fixture")
        val baseRow =
            ComponentConfigurationEntity(
                component = component,
                versionRange = "(,)",
                rowType = "BASE",
            )

        val baseConfig = makeConfig("(,)", externalRegistry = "ssh://base", vcsPath = "ssh://base-root")
        val overrideConfig = makeConfig("[1.0,2.0)", externalRegistry = null, vcsPath = null)

        val rows = callEmitMarkerOverrides(component, baseRow, baseConfig, overrideConfig)

        val registryRow = rows.single { it.overriddenAttribute == "vcs.externalRegistry" }
        assertEquals("[1.0,2.0)", registryRow.versionRange)
        assertNull(registryRow.vcsExternalRegistry)
        assertEquals(1, rows.count { it.overriddenAttribute == "vcs.settings" })
    }
}
