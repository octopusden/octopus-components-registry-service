package org.octopusden.octopus.components.registry.server.service.impl

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * CRS-C — legacy-read compatibility bridge for the dedicated `skipCommitCheck` flag.
 *
 * The v1–v3 contract (Jira plugin / RM) has no notion of `skipCommitCheck`; it only knows
 * `VCSSettings.externalRegistry`. This guard pins that the DB-mode resolver re-materializes the
 * legacy `externalRegistry = "NOT_AVAILABLE"` sentinel whenever the flag is set — bit-for-bit
 * identical to the pre-flag world — and that the flag WINS over any real registry value (they are
 * mutually exclusive by construction, Q13). Reverse: with the flag off, a real registry is emitted
 * untouched.
 *
 * Hand-built entity graph driven through `DatabaseComponentRegistryResolver` with a mocked
 * repository (the `RangeOnlyParityGuardTest` / `DatabaseComponentRegistryResolverTest` pattern) —
 * no Spring/DB context, so it runs under `:test`.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class SkipCommitCheckLegacyReadTest {

    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val dependencyMappingRepository: DependencyMappingRepository =
        mock(DependencyMappingRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private val versionRangeFactory = VersionRangeFactory(versionNames)
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @BeforeEach
    fun setUp() {
        resolver = DatabaseComponentRegistryResolver(
            componentRepository,
            dependencyMappingRepository,
            numericVersionFactory,
            versionRangeFactory,
            versionNames,
        )
    }

    private fun component(
        skipCommitCheck: Boolean,
        externalRegistry: String?,
    ): ComponentEntity {
        val comp = ComponentEntity(id = UUID.randomUUID(), componentKey = COMPONENT)
        comp.skipCommitCheck = skipCommitCheck
        comp.vcsExternalRegistry = externalRegistry
        val base = ComponentConfigurationEntity(
            component = comp,
            versionRange = ALL_VERSIONS,
            overriddenAttribute = null,
            rowType = "BASE",
            isSyntheticBase = false,
            buildSystem = "MAVEN",
            jiraProjectKey = "SCC",
            deprecated = false,
        )
        base.jiraMinorVersionFormat = "\$major"
        base.jiraReleaseVersionFormat = "\$major.\$minor.\$service"
        comp.configurations.add(base)
        return comp
    }

    private fun stub(comp: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(comp.componentKey)).thenReturn(comp)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(comp))
    }

    private fun resolvedExternalRegistry(comp: ComponentEntity): String? {
        stub(comp)
        val resolved = resolver.getResolvedComponentDefinition(COMPONENT, VERSION)
        assertNotNull(resolved, "component must resolve at $VERSION")
        return resolved!!.vcsSettings?.externalRegistry
    }

    @Test
    @DisplayName("skipCommitCheck=true (no real registry, no roots) → legacy externalRegistry = NOT_AVAILABLE")
    fun flagTrueEmitsSentinel() {
        assertEquals("NOT_AVAILABLE", resolvedExternalRegistry(component(skipCommitCheck = true, externalRegistry = null)))
    }

    @Test
    @DisplayName("skipCommitCheck=true WINS over a real registry → legacy externalRegistry = NOT_AVAILABLE")
    fun flagWinsOverRealRegistry() {
        assertEquals(
            "NOT_AVAILABLE",
            resolvedExternalRegistry(component(skipCommitCheck = true, externalRegistry = "some-registry")),
        )
    }

    @Test
    @DisplayName("skipCommitCheck=false → the real registry value is emitted untouched")
    fun realRegistryUntouched() {
        assertEquals(
            "some-registry",
            resolvedExternalRegistry(component(skipCommitCheck = false, externalRegistry = "some-registry")),
        )
    }

    @Test
    @DisplayName("skipCommitCheck=false, no registry, no roots → no externalRegistry on the legacy surface")
    fun noFlagNoRegistry() {
        assertNull(resolvedExternalRegistry(component(skipCommitCheck = false, externalRegistry = null)))
    }

    companion object {
        private const val COMPONENT = "sccComponent"
        private const val VERSION = "1.0.0"
    }
}
