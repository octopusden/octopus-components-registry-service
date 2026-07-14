package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * SYS-049: the git-history backfill records each component's first appearance
 * with the dedicated `MIGRATED` action (not `CREATE`). These baseline rows are
 * migration noise — one per component — and the Portal hides them by default,
 * so they must be distinguishable from genuine `CREATE` events authored through
 * the runtime API.
 *
 * Pure unit test on the lifted `resolveAction` mapping — no Spring, no DB, no Git.
 */
@Timeout(10, unit = TimeUnit.SECONDS)
class GitHistoryImportActionResolutionTest {
    @Test
    @DisplayName("SYS-049: a component's first appearance (no previous snapshot) resolves to MIGRATED")
    fun `SYS-049 first appearance resolves to MIGRATED`() {
        val action =
            GitHistoryImportServiceImpl.resolveAction(
                oldValue = null,
                newValue = mapOf("displayName" to "Widget"),
            )
        assertEquals("MIGRATED", action)
    }

    @Test
    @DisplayName("SYS-049: a disappearance (no current snapshot) still resolves to DELETE")
    fun `SYS-049 disappearance resolves to DELETE`() {
        val action =
            GitHistoryImportServiceImpl.resolveAction(
                oldValue = mapOf("displayName" to "Widget"),
                newValue = null,
            )
        assertEquals("DELETE", action)
    }

    @Test
    @DisplayName("SYS-049: a changed snapshot still resolves to UPDATE")
    fun `SYS-049 changed snapshot resolves to UPDATE`() {
        val action =
            GitHistoryImportServiceImpl.resolveAction(
                oldValue = mapOf("displayName" to "Widget"),
                newValue = mapOf("displayName" to "Gadget"),
            )
        assertEquals("UPDATE", action)
    }

    @Test
    @DisplayName("SYS-049: an unchanged snapshot resolves to null (no row)")
    fun `SYS-049 unchanged snapshot resolves to null`() {
        val action =
            GitHistoryImportServiceImpl.resolveAction(
                oldValue = mapOf("displayName" to "Widget"),
                newValue = mapOf("displayName" to "Widget"),
            )
        assertNull(action)
    }
}
