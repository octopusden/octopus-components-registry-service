package org.octopusden.octopus.components.registry.server.service.impl

import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.DefaultConfigParameters
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig

/**
 * Unit tests for the import-side CSV → ordered-list split. This is a
 * **restoration** of multi-value RM/SC that schema-v2 dropped: the DSL source
 * already stores `releaseManager` / `securityChampion` as a comma-separated
 * string validated by `\w+(,\w+)*` (no spaces), and
 * `ImportServiceImpl.buildComponentEntity` is the single site that splits that
 * CSV back into the ordered child collection (the accessor then trims / drops
 * blanks / keep-first dedupes — faithfully more lenient than the validator).
 * `buildComponentEntity` is private and has no dependency on any injected field,
 * so it is exercised via reflection (mirrors `ImportServiceImplVcsNameTest`).
 *
 * Values mirror the real orphan fixture
 * `component-resolver-core/src/test/resources/test-multi-release-managers/`
 * (valid `"torvalds,tanenbaum"` / `"stallman,torvalds,tanenbaum"`; invalid
 * spaced `"stallman, torvalds"`) so the restoration is demonstrably faithful.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ImportServicePeopleCsvSplitTest {

    private lateinit var service: ImportServiceImpl
    private lateinit var buildComponentEntityMethod: Method

    @BeforeEach
    fun setUp() {
        // buildComponentEntity now resolves displayName via commonDefaultsCache (the lazy
        // loadCommonDefaults). Stub it so the lazy yields a non-null DefaultConfigParameters
        // (componentDisplayName=null) → resolveDisplayName falls back to the component key.
        val configurationLoaderMock = mock(EscrowConfigurationLoader::class.java)
        Mockito.`when`(configurationLoaderMock.loadCommonDefaults(emptyMap()))
            .thenReturn(DefaultConfigParameters())
        service = ImportServiceImpl(
            gitResolver = mock(ComponentRegistryResolverImpl::class.java),
            dbResolver = mock(DatabaseComponentRegistryResolver::class.java),
            componentSourceRepository = mock(ComponentSourceRepository::class.java),
            sourceRegistry = mock(ComponentSourceRegistry::class.java),
            configurationLoader = configurationLoaderMock,
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
        )

        buildComponentEntityMethod = ImportServiceImpl::class.java
            .getDeclaredMethod("buildComponentEntity", String::class.java, EscrowModuleConfig::class.java)
        buildComponentEntityMethod.isAccessible = true
    }

    private fun buildEntity(configure: EscrowModuleConfig.() -> Unit): ComponentEntity {
        val cfg = EscrowModuleConfig().apply(configure)
        return buildComponentEntityMethod.invoke(service, "svc", cfg) as ComponentEntity
    }

    private fun EscrowModuleConfig.setField(name: String, value: Any?) {
        val f = EscrowModuleConfig::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(this, value)
    }

    @Test
    @DisplayName("SYS-044: import splits releaseManager CSV \"stallman,torvalds,tanenbaum\" into ordered list (fixture restore)")
    fun `SYS-044 import splits releaseManager CSV into ordered list`() {
        val entity = buildEntity { setField("releaseManager", "stallman,torvalds,tanenbaum") }
        assertEquals(listOf("stallman", "torvalds", "tanenbaum"), entity.releaseManagerUsernames())
    }

    @Test
    @DisplayName("SYS-044: import splits securityChampion CSV \"torvalds,tanenbaum\" into ordered [torvalds, tanenbaum]")
    fun `SYS-044 import splits securityChampion CSV into ordered list`() {
        val entity = buildEntity { setField("securityChampion", "torvalds,tanenbaum") }
        assertEquals(listOf("torvalds", "tanenbaum"), entity.securityChampionUsernames())
    }

    @Test
    @DisplayName("SYS-044: import is lenient about the spaced (validator-invalid) form: \"stallman, torvalds\" -> [stallman, torvalds]")
    fun `SYS-044 import is lenient about the spaced validator-invalid form`() {
        // The DSL validator rejects spaces, but the accessor trims so a stray
        // space (or blank/trailing comma) still restores correctly.
        val entity = buildEntity { setField("releaseManager", "stallman, torvalds") }
        assertEquals(listOf("stallman", "torvalds"), entity.releaseManagerUsernames())
    }

    @Test
    @DisplayName("SYS-044: import canonicalizes: \" stallman ,, stallman ,torvalds\" -> [stallman, torvalds] (trim/drop-blank/dedupe)")
    fun `SYS-044 import canonicalizes trim drop-blank keep-first dedupe`() {
        val entity = buildEntity { setField("releaseManager", " stallman ,, stallman ,torvalds") }
        assertEquals(listOf("stallman", "torvalds"), entity.releaseManagerUsernames())
    }

    @Test
    @DisplayName("SYS-044: single-value CSV \"torvalds\" round-trips to [torvalds] (legacy fixture compat)")
    fun `SYS-044 import single-value CSV round-trips to one-element list`() {
        val entity = buildEntity { setField("releaseManager", "torvalds") }
        assertEquals(listOf("torvalds"), entity.releaseManagerUsernames())
    }

    @Test
    @DisplayName("SYS-044: null CSV -> empty ordered list")
    fun `SYS-044 import null CSV yields empty ordered list`() {
        val entity = buildEntity { /* releaseManager left null */ }
        assertTrue(entity.releaseManagerUsernames().isEmpty())
        assertTrue(entity.securityChampionUsernames().isEmpty())
    }
}
