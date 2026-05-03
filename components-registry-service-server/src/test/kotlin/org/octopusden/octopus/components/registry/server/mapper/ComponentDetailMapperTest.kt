package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import java.util.UUID

/**
 * SYS-039 — `ComponentEntity.toDetailResponse()` surfaces six new fields
 * required by the §7.0 Wave 2 portal General-tab editor:
 *   `groupId`, `releaseManager`, `securityChampion`, `copyright`,
 *   `releasesInDefaultBranch`, `labels`.
 *
 * The mapper just projects entity values verbatim (with one shape change
 * — `labels: Array<String>` becomes `Set<String>` on the wire). These
 * tests pin the round-trip from entity to DTO so a future refactor can't
 * silently drop a field; the V4 controller test in
 * BaseComponentsRegistryServiceTest covers the integrated read path.
 */
class ComponentDetailMapperTest {
    private fun baseComponent() =
        ComponentEntity(
            id = UUID.randomUUID(),
            name = "alpha",
        )

    @Test
    @DisplayName("default entity → all six SYS-039 fields are absent / empty")
    fun defaults_allAbsent() {
        val response = baseComponent().toDetailResponse()

        assertNull(response.groupId)
        assertNull(response.releaseManager)
        assertNull(response.securityChampion)
        assertNull(response.copyright)
        assertNull(response.releasesInDefaultBranch)
        assertTrue(response.labels.isEmpty())
    }

    @Test
    @DisplayName("populated entity → all six SYS-039 fields propagate")
    fun populated_allPropagate() {
        val component =
            baseComponent().also {
                it.groupId = "org.octopusden.alpha"
                it.releaseManager = "rm-user"
                it.securityChampion = "sc-user"
                it.copyright = "(c) 2026 OpenWay"
                it.releasesInDefaultBranch = true
                it.labels = arrayOf("backend", "internal")
            }

        val response = component.toDetailResponse()

        assertEquals("org.octopusden.alpha", response.groupId)
        assertEquals("rm-user", response.releaseManager)
        assertEquals("sc-user", response.securityChampion)
        assertEquals("(c) 2026 OpenWay", response.copyright)
        assertEquals(true, response.releasesInDefaultBranch)
        assertEquals(setOf("backend", "internal"), response.labels)
    }

    @Test
    @DisplayName("labels Array → Set conversion deduplicates while preserving membership")
    fun labels_arrayToSet_dedupes() {
        val component =
            baseComponent().also {
                it.labels = arrayOf("a", "b", "a", "c")
            }

        val response = component.toDetailResponse()

        assertEquals(setOf("a", "b", "c"), response.labels)
    }

    @Test
    @DisplayName("releasesInDefaultBranch=false stays distinct from null")
    fun releasesInDefaultBranch_explicitFalse() {
        val component =
            baseComponent().also {
                it.releasesInDefaultBranch = false
            }

        val response = component.toDetailResponse()

        assertEquals(false, response.releasesInDefaultBranch)
    }
}
