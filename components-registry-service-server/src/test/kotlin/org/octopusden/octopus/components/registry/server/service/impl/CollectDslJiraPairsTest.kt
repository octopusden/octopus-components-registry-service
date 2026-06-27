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
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingRepository
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
            componentArtifactMappingRepository = mock(ComponentArtifactMappingRepository::class.java),
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

    // The collector receives MERGED per-range configs (the Groovy loader has already
    // layered range jira blocks over the base, including the inherited
    // component{versionPrefix}). The effective claim of a range is therefore the
    // merged (projectKey, versionPrefix) — exactly what legacy
    // validateJiraProjectKeyAndVersionPrefixIntersections bucketed, and what the
    // resolver serves. Bucketing raw SCALAR_OVERRIDE rows instead (a projectKey-only
    // override row carries NULL prefix) fabricates (key, null) claims the legacy
    // contract never made — prod shape: one component legitimately owns
    // (project, null) while others claim that project only WITH their
    // inherited/explicit prefixes via projectKey-only range overrides.

    @Test
    @DisplayName("range overriding ONLY versionPrefix claims the merged (projectKey, newPrefix) pair")
    fun prefixOnlyOverride_claimsMergedPair() {
        val pairs = collect(
            "comp",
            listOf(
                config("(,0),[0,)", "PROJ", "base"),
                // Loader-merged range config: inherited projectKey + overridden prefix.
                config("[2,)", "PROJ", "other"),
            ),
        )
        assertEquals(
            listOf(
                UniquenessJiraPair("comp", "PROJ", "base"),
                UniquenessJiraPair("comp", "PROJ", "other"),
            ),
            pairs,
        )
    }

    @Test
    @DisplayName("prod shape: projectKey-only override claims (overrideKey, INHERITED prefix), not (overrideKey, null)")
    fun projectKeyOverride_claimsMergedInheritedPrefix() {
        val pairs = collect(
            "comp",
            listOf(
                config("(,0),[0,)", "PROJM", "EditorModel"),
                // Loader-merged: override sets projectKey only, componentInfo inherited.
                config("(52.0.1-6,52.0.1-21]", "PROJW", "EditorModel"),
            ),
        )
        assertEquals(
            listOf(
                UniquenessJiraPair("comp", "PROJM", "EditorModel"),
                UniquenessJiraPair("comp", "PROJW", "EditorModel"),
            ),
            pairs,
        )
    }

    @Test
    @DisplayName("base WITHOUT jira + range introducing a projectKey claims the merged (overrideKey, overridePrefix)")
    fun noBaseJira_overrideIntroducesProjectKey() {
        val pairs = collect(
            "comp",
            listOf(
                config("(,0),[0,)", null, null),
                config("[2,)", "INTRO", "pfx"),
            ),
        )
        assertEquals(listOf(UniquenessJiraPair("comp", "INTRO", "pfx")), pairs)
    }

    @Test
    @DisplayName("archived component claims no pairs")
    fun archivedClaimsNothing() {
        val pairs = collect("comp", listOf(config("(,0),[0,)", "PROJ", "pfx", archived = true)))
        assertEquals(emptyList<UniquenessJiraPair>(), pairs)
    }
}
