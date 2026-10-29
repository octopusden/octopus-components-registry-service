package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * CRS-A — motivation guard for the clear rule at the resolver layer.
 *
 * The version-format resolver falls back with a Kotlin `?:` chain
 * (`buildFmt = jiraBuildVersionFormat ?: releaseFmt`). That chain only fires
 * on a genuine NULL: an empty-string column is non-null, so it would defeat the
 * fallback and yield an empty format string. This is exactly why CRS-A converts
 * a `""` clear to NULL on the write side rather than storing the empty string.
 *
 * These tests read the entity columns directly through the resolver:
 *   - a cleared (NULL) `jiraBuildVersionFormat` falls back to the release format;
 *   - a would-be `""` column does NOT fall back (the hazard the write rule avoids).
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ClearedFormatFallbackResolverTest {
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

    private fun seed(
        key: String,
        buildFormat: String?,
    ): ComponentEntity {
        val comp = ComponentEntity(id = UUID.randomUUID(), componentKey = key)
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                deprecated = false,
            )
        base.jiraProjectKey = "PK"
        base.jiraReleaseVersionFormat = "\$major.\$minor.\$service"
        base.jiraBuildVersionFormat = buildFormat
        comp.configurations.add(base)
        `when`(componentRepository.findByComponentKey(key)).thenReturn(comp)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(comp))
        return comp
    }

    @Test
    @DisplayName("cleared (NULL) buildVersionFormat falls back to the release format")
    fun `null build format falls back to release`() {
        seed("CLEARED_NULL_BUILD_FMT", buildFormat = null)
        val cfg = resolver.getResolvedComponentDefinition("CLEARED_NULL_BUILD_FMT", "1.2.3")
        assertNotNull(cfg)
        assertEquals(
            "\$major.\$minor.\$service",
            cfg!!.jiraConfiguration?.componentVersionFormat?.buildVersionFormat,
            "a cleared (null) buildVersionFormat must fall back to releaseVersionFormat",
        )
    }

    @Test
    @DisplayName("empty-string buildVersionFormat does NOT fall back — the hazard the write-side clear rule avoids")
    fun `empty build format does not fall back`() {
        seed("EMPTY_BUILD_FMT", buildFormat = "")
        val cfg = resolver.getResolvedComponentDefinition("EMPTY_BUILD_FMT", "1.2.3")
        assertNotNull(cfg)
        assertEquals(
            "",
            cfg!!.jiraConfiguration?.componentVersionFormat?.buildVersionFormat,
            "an empty-string column stays empty (non-null defeats `?:` fallback) — hence write-side stores NULL",
        )
    }
}
