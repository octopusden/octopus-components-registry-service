package org.octopusden.octopus.components.registry.server.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for the ordered multi-value people accessors on [ComponentEntity]
 * (`releaseManagerUsernames` / `replaceReleaseManagerUsernames` and the
 * security-champion pair). `replace*Usernames` is the single canonicalization
 * point shared by every write path (create / patch / import), so these tests
 * pin the canonical form (trim → drop blank → keep-first dedupe) and the
 * ordered clear/re-add semantics once.
 */
class ComponentPeopleAccessorTest {
    private fun component(): ComponentEntity = ComponentEntity(id = UUID.randomUUID(), componentKey = "svc")

    @Test
    @DisplayName("SYS-044: replaceReleaseManagerUsernames preserves order and assigns sortOrder by index")
    fun `SYS-044 replaceReleaseManagerUsernames preserves order and assigns sortOrder by index`() {
        val c = component()
        c.replaceReleaseManagerUsernames(listOf("alice", "bob", "carol"))

        assertEquals(listOf("alice", "bob", "carol"), c.releaseManagerUsernames())
        assertEquals(listOf(0, 1, 2), c.releaseManagers.map { it.sortOrder })
        assertTrue(c.releaseManagers.all { it.component === c }, "child rows must back-reference the parent")
    }

    @Test
    @DisplayName("SYS-044: releaseManagerUsernames() sorts by sortOrder, not collection/heap order")
    fun `SYS-044 releaseManagerUsernames sorts by sortOrder not collection or heap order`() {
        val c = component()
        c.replaceReleaseManagerUsernames(listOf("alice", "bob", "carol"))
        c.releaseManagers.reverse() // simulate non-deterministic heap-scan order

        assertEquals(listOf("alice", "bob", "carol"), c.releaseManagerUsernames())
    }

    @Test
    @DisplayName("SYS-044: canonicalization (release manager): [\" alice \", \"\", \"alice\", \"bob\"] -> [\"alice\", \"bob\"]")
    fun `SYS-044 release manager canonicalization trim drop-blank keep-first dedupe`() {
        val c = component()
        c.replaceReleaseManagerUsernames(listOf(" alice ", "", "alice", "bob"))

        assertEquals(listOf("alice", "bob"), c.releaseManagerUsernames())
    }

    @Test
    @DisplayName("SYS-044: canonicalization (security champion): [\" alice \", \"\", \"alice\", \"bob\"] -> [\"alice\", \"bob\"]")
    fun `SYS-044 security champion canonicalization trim drop-blank keep-first dedupe`() {
        val c = component()
        c.replaceSecurityChampionUsernames(listOf(" alice ", "", "alice", "bob"))

        assertEquals(listOf("alice", "bob"), c.securityChampionUsernames())
    }

    @Test
    @DisplayName("SYS-044: empty list clears the ordered collection")
    fun `SYS-044 empty list clears the ordered collection`() {
        val c = component()
        c.replaceReleaseManagerUsernames(listOf("alice", "bob"))
        c.replaceReleaseManagerUsernames(emptyList())

        assertTrue(c.releaseManagerUsernames().isEmpty())
        assertTrue(c.releaseManagers.isEmpty())
    }

    @Test
    @DisplayName("SYS-044: replace re-numbers sortOrder from 0 (reorder edit)")
    fun `SYS-044 replace re-numbers sortOrder from 0 on reorder`() {
        val c = component()
        c.replaceReleaseManagerUsernames(listOf("a", "b", "c"))
        c.replaceReleaseManagerUsernames(listOf("c", "a"))

        assertEquals(listOf("c", "a"), c.releaseManagerUsernames())
        assertEquals(listOf(0, 1), c.releaseManagers.map { it.sortOrder })
    }

    @Test
    @DisplayName("SYS-044: release manager and security champion lists are independent")
    fun `SYS-044 release manager and security champion lists are independent`() {
        val c = component()
        c.replaceReleaseManagerUsernames(listOf("rm1", "rm2"))
        c.replaceSecurityChampionUsernames(listOf("sc1"))

        assertEquals(listOf("rm1", "rm2"), c.releaseManagerUsernames())
        assertEquals(listOf("sc1"), c.securityChampionUsernames())
    }
}
