package org.octopusden.octopus.components.registry.server.service.impl

import java.lang.reflect.Method
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionDockerImageRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * `collectDslJiraPairs` must emit ONLY the jira (projectKey, versionPrefix) pairs that
 * `importModule` persists with a non-null `jiraProjectKey` — the set the API-side
 * uniqueness check later reads. The DSL loader inherits `projectKey` onto every
 * per-range config, so a range that overrides only `versionPrefix` must NOT fabricate
 * a phantom `(projectKey, overridePrefix)` claim (it is persisted as a SCALAR_OVERRIDE
 * row whose `jiraProjectKey` is null) — that would false-positive the migration gate
 * against a component that legitimately owns the override prefix.
 */
class CollectDslJiraPairsTest {

    private lateinit var service: ImportServiceImpl
    private lateinit var collectMethod: Method

    @BeforeEach
    fun setUp() {
        service = ImportServiceImpl(
            gitResolver = mock(ComponentRegistryResolverImpl::class.java),
            dbResolver = mock(DatabaseComponentRegistryResolver::class.java),
            componentSourceRepository = mock(ComponentSourceRepository::class.java),
            sourceRegistry = mock(ComponentSourceRegistry::class.java),
            configurationLoader = mock(EscrowConfigurationLoader::class.java),
            configSyncService = mock(ConfigSyncService::class.java),
            componentRepository = mock(ComponentRepository::class.java),
            configurationRepository = mock(ComponentConfigurationRepository::class.java),
            componentGroupRepository = mock(ComponentGroupRepository::class.java),
            systemRepository = mock(SystemRepository::class.java),
            toolRepository = mock(ToolRepository::class.java),
            labelRepository = mock(LabelRepository::class.java),
            componentLabelRepository = mock(ComponentLabelRepository::class.java),
            componentRequiredToolRepository = mock(ComponentRequiredToolRepository::class.java),
            componentBuildToolBeanRepository = mock(ComponentBuildToolBeanRepository::class.java),
            mavenArtifactRepository = mock(DistributionMavenArtifactRepository::class.java),
            dockerImageRepository = mock(DistributionDockerImageRepository::class.java),
            versionRangeFactory = VersionRangeFactory(VersionNames("serviceCBranch", "serviceC", "minorC")),
        )
        collectMethod = ImportServiceImpl::class.java
            .getDeclaredMethod("collectDslJiraPairs", String::class.java, List::class.java)
        collectMethod.isAccessible = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun collect(componentKey: String, configs: List<EscrowModuleConfig>): List<UniquenessJiraPair> =
        collectMethod.invoke(service, componentKey, configs) as List<UniquenessJiraPair>

    private fun config(
        versionRange: String?,
        projectKey: String?,
        versionPrefix: String?,
        archived: Boolean = false,
    ): EscrowModuleConfig {
        val cfg = EscrowModuleConfig()
        fun set(name: String, value: Any?) {
            val f = EscrowModuleConfig::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(cfg, value)
        }
        set("versionRange", versionRange)
        set("archived", archived)
        if (projectKey != null) {
            val componentInfo = versionPrefix?.let { ComponentInfo(it, "\$versionPrefix-\$baseVersionFormat") }
            set("jiraConfiguration", JiraComponent(projectKey, projectKey, null, componentInfo, false, false))
        }
        return cfg
    }

    @Test
    @DisplayName("base pair is emitted with the base versionPrefix")
    fun basePairEmitted() {
        val pairs = collect("comp", listOf(config("(,0),[0,)", "PROJ", "pfx")))
        assertEquals(listOf(UniquenessJiraPair("comp", "PROJ", "pfx")), pairs)
    }

    @Test
    @DisplayName("range overriding ONLY versionPrefix claims NO extra pair (loader-inherited projectKey)")
    fun prefixOnlyOverride_noPhantomPair() {
        val pairs = collect(
            "comp",
            listOf(
                config("(,0),[0,)", "PROJ", "base"),
                // DSL loader merges base projectKey onto the range config; only the prefix differs.
                config("[2,)", "PROJ", "other"),
            ),
        )
        assertEquals(listOf(UniquenessJiraPair("comp", "PROJ", "base")), pairs)
    }

    @Test
    @DisplayName("range overriding projectKey itself claims (overrideKey, null) — the SCALAR_OVERRIDE row shape")
    fun projectKeyOverride_claimsKeyWithNullPrefix() {
        val pairs = collect(
            "comp",
            listOf(
                config("(,0),[0,)", "PROJ", "base"),
                config("[2,)", "OTHER", "other"),
            ),
        )
        assertEquals(
            listOf(
                UniquenessJiraPair("comp", "PROJ", "base"),
                UniquenessJiraPair("comp", "OTHER", null),
            ),
            pairs,
        )
    }

    @Test
    @DisplayName("base WITHOUT jira + range introducing a projectKey claims (overrideKey, null)")
    fun noBaseJira_overrideIntroducesProjectKey() {
        val pairs = collect(
            "comp",
            listOf(
                config("(,0),[0,)", null, null),
                config("[2,)", "INTRO", "pfx"),
            ),
        )
        assertEquals(listOf(UniquenessJiraPair("comp", "INTRO", null)), pairs)
    }

    @Test
    @DisplayName("archived component claims no pairs")
    fun archivedClaimsNothing() {
        val pairs = collect("comp", listOf(config("(,0),[0,)", "PROJ", "pfx", archived = true)))
        assertEquals(emptyList<UniquenessJiraPair>(), pairs)
    }
}
