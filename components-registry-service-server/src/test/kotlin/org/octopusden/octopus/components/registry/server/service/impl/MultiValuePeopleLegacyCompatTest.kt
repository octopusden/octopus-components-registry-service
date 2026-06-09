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
 * Legacy v1/v2/v3 compatibility: the ordered multi-value people list is joined
 * back into a comma-string when resolving an [org.octopusden.octopus.escrow
 * .configuration.model.EscrowModuleConfig] (the single join site is
 * `EntityMappers.buildEscrowModuleConfig`). This keeps the legacy DTOs
 * (`String?`) non-breaking and pins the empty-list → null rule plus the
 * single-value round-trip that the DB-backed controller fixtures depend on
 * (review Finding 5).
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MultiValuePeopleLegacyCompatTest {

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

    private fun makeComponent(key: String): ComponentEntity {
        val comp = ComponentEntity(id = UUID.randomUUID(), componentKey = key)
        val base = ComponentConfigurationEntity(
            component = comp,
            versionRange = ALL_VERSIONS,
            overriddenAttribute = null,
            rowType = "BASE",
            deprecated = false,
        )
        comp.configurations.add(base)
        return comp
    }

    private fun stub(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    @Test
    @DisplayName("SYS-044: multi-value lists join back to the original DSL comma-string (fixture restore round-trip)")
    fun `SYS-044 multi-value lists join back to the original DSL comma-string`() {
        // Mirrors the orphan fixture `test-multi-release-managers` values: the
        // ordered list must re-join to the exact DSL CSV the import split from.
        val comp = makeComponent("MULTI_PEOPLE")
        comp.replaceReleaseManagerUsernames(listOf("stallman", "torvalds", "tanenbaum"))
        comp.replaceSecurityChampionUsernames(listOf("torvalds", "tanenbaum"))
        stub(comp)

        val cfg = resolver.getResolvedComponentDefinition("MULTI_PEOPLE", "1.0.0")
        assertNotNull(cfg)
        assertEquals("stallman,torvalds,tanenbaum", cfg!!.releaseManager)
        assertEquals("torvalds,tanenbaum", cfg.securityChampion)
    }

    @Test
    @DisplayName("SYS-044: empty people lists join to null (not empty string)")
    fun `SYS-044 empty people lists join to null not empty string`() {
        val comp = makeComponent("NO_PEOPLE")
        // no managers / champions set
        stub(comp)

        val cfg = resolver.getResolvedComponentDefinition("NO_PEOPLE", "1.0.0")
        assertNotNull(cfg)
        assertNull(cfg!!.releaseManager)
        assertNull(cfg.securityChampion)
    }

    @Test
    @DisplayName("SYS-044: single-value list round-trips to the same string (DB-backed fixture compat)")
    fun `SYS-044 single-value list round-trips to the same string`() {
        val comp = makeComponent("SINGLE_PERSON")
        comp.replaceReleaseManagerUsernames(listOf("user"))
        comp.replaceSecurityChampionUsernames(listOf("user"))
        stub(comp)

        val cfg = resolver.getResolvedComponentDefinition("SINGLE_PERSON", "1.0.0")
        assertNotNull(cfg)
        assertEquals("user", cfg!!.releaseManager)
        assertEquals("user", cfg.securityChampion)
    }

    @Test
    @DisplayName("MIG-051: backfilled displayName (== componentKey) collapses to null on the legacy wire")
    fun `MIG-051 displayName equal to componentKey resolves to null componentDisplayName`() {
        // displayName is now NOT NULL in the DB and defaults to componentKey when the
        // DSL declares no componentDisplayName (see ComponentEntity.displayName). Prod
        // 2.0.87 served v1/v2/v3 `$.name` as null in that case, so the legacy resolver
        // must collapse the backfill sentinel (displayName == componentKey) back to null
        // — otherwise every previously-unnamed component flips `$.name` NULL → STRING
        // and breaks wire-compat (compat gate [1.7], 6399 divergences).
        val comp = makeComponent("NO_DISPLAY_NAME")
        // makeComponent leaves displayName at its default (== componentKey)
        assertEquals("NO_DISPLAY_NAME", comp.displayName)
        stub(comp)

        val cfg = resolver.getResolvedComponentDefinition("NO_DISPLAY_NAME", "1.0.0")
        assertNotNull(cfg)
        assertNull(cfg!!.componentDisplayName)
    }

    @Test
    @DisplayName("MIG-051: a real displayName (!= componentKey) is preserved on the legacy wire")
    fun `MIG-051 displayName differing from componentKey is preserved`() {
        val comp = makeComponent("HAS_DISPLAY_NAME")
        comp.displayName = "Human Friendly Name"
        stub(comp)

        val cfg = resolver.getResolvedComponentDefinition("HAS_DISPLAY_NAME", "1.0.0")
        assertNotNull(cfg)
        assertEquals("Human Friendly Name", cfg!!.componentDisplayName)
    }

    @Test
    @DisplayName("MIG-051: list path (getComponentById) also collapses a backfilled displayName to null")
    fun `MIG-051 list path collapses backfilled displayName to null`() {
        // The list endpoint GET /rest/api/2/components serializes `$.components[N].name`
        // via toEscrowModule → buildEscrowModuleConfig (same join site). Pin that the
        // per-component fix equally covers the list path (the 6399 divergences included
        // `$.components[N].name`).
        val comp = makeComponent("NO_DISPLAY_NAME_LIST")
        stub(comp)

        val module = resolver.getComponentById("NO_DISPLAY_NAME_LIST")
        assertNotNull(module)
        assertNull(module!!.moduleConfigurations.first().componentDisplayName)
    }
}
