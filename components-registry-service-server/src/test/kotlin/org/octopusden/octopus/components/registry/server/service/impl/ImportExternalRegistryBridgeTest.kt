package org.octopusden.octopus.components.registry.server.service.impl

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity

/**
 * CRS-C — the import external-registry ⟷ skipCommitCheck fold ([applyImportedExternalRegistry]).
 *
 * The import is authoritative, so the bridge must set BOTH fields on every call — including the
 * re-import/resync transitions where a component that previously carried the NOT_AVAILABLE sentinel
 * (skipCommitCheck=true) now declares a real registry or none. These pre-seed the entity with the
 * stale state and assert the bridge fully overwrites it (no stuck flag). Plain unit test — `:test`.
 */
@Timeout(10, unit = TimeUnit.SECONDS)
class ImportExternalRegistryBridgeTest {

    private fun entity(
        skipCommitCheck: Boolean,
        vcsExternalRegistry: String?,
    ) = ComponentEntity(componentKey = "c").also {
        it.skipCommitCheck = skipCommitCheck
        it.vcsExternalRegistry = vcsExternalRegistry
    }

    @Test
    @DisplayName("NOT_AVAILABLE → flag on, registry cleared (sentinel never stored)")
    fun sentinelSetsFlag() {
        val e = entity(skipCommitCheck = false, vcsExternalRegistry = "stale")
        applyImportedExternalRegistry(e, "NOT_AVAILABLE")
        assertTrue(e.skipCommitCheck)
        assertNull(e.vcsExternalRegistry, "the sentinel is never stored in the registry column")
    }

    @Test
    @DisplayName("re-import transition: was flag=true, DSL now a real registry → flag reset off, registry set")
    fun realRegistryResetsStaleFlag() {
        val e = entity(skipCommitCheck = true, vcsExternalRegistry = null)
        applyImportedExternalRegistry(e, "some-registry")
        assertFalse(e.skipCommitCheck, "a real registry must reset a previously-set flag")
        assertEquals("some-registry", e.vcsExternalRegistry)
    }

    @Test
    @DisplayName("re-import transition: was flag=true, DSL now has NO external registry → flag reset off, registry null")
    fun droppedRegistryResetsStaleFlag() {
        val e = entity(skipCommitCheck = true, vcsExternalRegistry = null)
        applyImportedExternalRegistry(e, null)
        assertFalse(e.skipCommitCheck, "dropping the registry must reset a previously-set flag")
        assertNull(e.vcsExternalRegistry)
    }

    @Test
    @DisplayName("re-import transition: was a real registry, DSL now NOT_AVAILABLE → flag on, old registry cleared")
    fun sentinelClearsPreviousRealRegistry() {
        val e = entity(skipCommitCheck = false, vcsExternalRegistry = "old-registry")
        applyImportedExternalRegistry(e, "NOT_AVAILABLE")
        assertTrue(e.skipCommitCheck)
        assertNull(e.vcsExternalRegistry)
    }

    companion object {
        private fun assertEquals(expected: String?, actual: String?) =
            org.junit.jupiter.api.Assertions.assertEquals(expected, actual)
    }
}
