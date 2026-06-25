package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression guard for the memoized `EscrowModuleConfig` field lookup (GH #365 Fix B).
 *
 * The full unpaged-list mapper (`buildEscrowModuleConfig`) calls `setField` ~22 times per resolved
 * config; before the fix each call did `EscrowModuleConfig::class.java.getDeclaredField(name)`,
 * costing tens of thousands of reflective lookups per `GET /rest/api/3/components`. The fix builds
 * the field map once. This test proves discovery happens **once per name** (identical `Field`
 * instance on repeated calls) and that unknown names resolve to `null` (preserving the
 * silent-ignore / forward-compat contract). `escrowModuleConfigField` is `internal`, visible from
 * this same-module test source set.
 */
class EntityMappersSetFieldCacheTest {

    @Test
    @DisplayName("repeated lookups return the identical cached Field instance (discovered once)")
    fun returnsSameInstanceAcrossCalls() {
        val first = escrowModuleConfigField("distribution")
        val second = escrowModuleConfigField("distribution")
        assertSame(first, second, "field lookup must be memoized — same Field instance on repeated calls")
    }

    @Test
    @DisplayName("unknown / synthetic field names resolve to null and are silently ignored")
    fun unknownNamesResolveToNull() {
        assertNull(escrowModuleConfigField("noSuchFieldAtAll"))
        // Groovy injects synthetic fields like `$staticClassInfo`; these are filtered out of the map.
        assertNull(escrowModuleConfigField("\$staticClassInfo"))
    }

    @Test
    @DisplayName("every name written by buildEscrowModuleConfig resolves to a real declared field")
    fun allSetFieldNamesResolve() {
        // Drift guard: if a future EscrowModuleConfig refactor renames one of these, the mapper
        // would start silently dropping that value — catch it here instead of in production.
        ALL_SET_FIELD_NAMES.forEach { name ->
            val field = escrowModuleConfigField(name)
            assertTrue(
                field != null && field.isAccessible,
                "EscrowModuleConfig must expose an accessible declared field '$name' (used by buildEscrowModuleConfig)",
            )
        }
    }

    companion object {
        /** The full set of field names `buildEscrowModuleConfig`/`toResolvedEscrowModuleConfig` write via `setField`. */
        private val ALL_SET_FIELD_NAMES =
            listOf(
                "versionRange",
                "buildSystem",
                "buildFilePath",
                "deprecated",
                "buildConfiguration",
                "vcsSettings",
                "distribution",
                "jiraConfiguration",
                "componentDisplayName",
                "componentOwner",
                "system",
                "clientCode",
                "solution",
                "parentComponent",
                "archived",
                "releaseManager",
                "securityChampion",
                "copyright",
                "releasesInDefaultBranch",
                "labels",
                "groupIdPattern",
                "artifactIdPattern",
            )
    }
}
