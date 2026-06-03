package org.octopusden.octopus.components.registry.server.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenMock
import org.octopusden.cloud.commons.security.SecurityService
import org.octopusden.cloud.commons.security.dto.Role
import org.octopusden.cloud.commons.security.dto.User
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.springframework.beans.factory.ObjectProvider
import java.util.UUID

/**
 * Fast (no-Spring) unit coverage for [PermissionEvaluator.canEditComponent], pinning
 * each branch of the per-component edit gate. The full HTTP→DB behaviour (403/200,
 * field-overrides, `canEdit` flag) lives in `ComponentOwnershipEditSecurityTest`;
 * this suite isolates the decision logic and runs in the Compile&UT fast gate.
 */
class PermissionEvaluatorTest {
    private val sec = mock(SecurityService::class.java)
    private val repo = mock(ComponentRepository::class.java)

    // PermissionEvaluator takes ObjectProvider<ComponentRepository> (optional: absent in
    // the no-db boot mode). Wrap the repo mock in a provider that hands it back.
    @Suppress("UNCHECKED_CAST")
    private val repoProvider =
        (mock(ObjectProvider::class.java) as ObjectProvider<ComponentRepository>)
            .also { whenMock(it.getIfAvailable()).thenReturn(repo) }

    private val evaluator = PermissionEvaluator(sec, repoProvider)

    private fun loginAs(user: User) = whenMock(sec.getCurrentUser()).thenReturn(user)

    private fun editor(username: String) =
        User(username, setOf(Role("ROLE_EDITOR", setOf("ACCESS_COMPONENTS", "EDIT_COMPONENTS"))), emptySet())

    private fun admin(username: String) =
        User(
            username,
            setOf(Role("ROLE_ADMIN", setOf("ACCESS_COMPONENTS", "EDIT_COMPONENTS", "EDIT_ANY_COMPONENT"))),
            emptySet(),
        )

    private fun viewer(username: String) =
        User(username, setOf(Role("ROLE_VIEWER", setOf("ACCESS_COMPONENTS"))), emptySet())

    private fun noAccess(username: String) =
        User(username, setOf(Role("ROLE_NO_ACCESS", emptySet())), emptySet())

    private fun stub(
        id: UUID,
        owner: String? = null,
        rm: List<String> = emptyList(),
        sc: List<String> = emptyList(),
    ) {
        whenMock(repo.findComponentOwnerById(id)).thenReturn(owner)
        whenMock(repo.findReleaseManagerUsernames(id)).thenReturn(rm)
        whenMock(repo.findSecurityChampionUsernames(id)).thenReturn(sc)
    }

    @Test
    @DisplayName("no ACCESS_COMPONENTS → deny, without any DB lookup")
    fun `no access denied without db hit`() {
        loginAs(noAccess("bob"))
        val id = UUID.randomUUID()
        assertFalse(evaluator.canEditComponent(id.toString()))
        verify(repo, never()).findComponentOwnerById(id)
    }

    @Test
    @DisplayName("EDIT_ANY_COMPONENT (admin) → allow, without any DB lookup")
    fun `admin bypass without db hit`() {
        loginAs(admin("alice"))
        val id = UUID.randomUUID()
        assertTrue(evaluator.canEditComponent(id.toString()))
        verify(repo, never()).findComponentOwnerById(id)
    }

    @Test
    @DisplayName("owner matches → allow, even without EDIT_COMPONENTS")
    fun `owner allowed`() {
        loginAs(viewer("bob"))
        val id = UUID.randomUUID()
        stub(id, owner = "bob")
        assertTrue(evaluator.canEditComponent(id.toString()))
    }

    @Test
    @DisplayName("owner match is case-insensitive (stored 'Bob', token 'bob')")
    fun `owner case insensitive`() {
        loginAs(viewer("bob"))
        val id = UUID.randomUUID()
        stub(id, owner = "Bob")
        assertTrue(evaluator.canEditComponent(id.toString()))
    }

    @Test
    @DisplayName("release manager membership → allow, even without EDIT_COMPONENTS")
    fun `release manager allowed`() {
        loginAs(viewer("dave"))
        val id = UUID.randomUUID()
        stub(id, owner = "bob", rm = listOf("dave", "erin"))
        assertTrue(evaluator.canEditComponent(id.toString()))
    }

    @Test
    @DisplayName("security champion membership → allow, even without EDIT_COMPONENTS")
    fun `security champion allowed`() {
        loginAs(viewer("erin"))
        val id = UUID.randomUUID()
        stub(id, owner = "bob", sc = listOf("erin"))
        assertTrue(evaluator.canEditComponent(id.toString()))
    }

    @Test
    @DisplayName("editor who is not owner/RM/SC → deny")
    fun `unrelated editor denied`() {
        loginAs(editor("frank"))
        val id = UUID.randomUUID()
        stub(id, owner = "bob", rm = listOf("dave"), sc = listOf("erin"))
        assertFalse(evaluator.canEditComponent(id.toString()))
    }

    @Test
    @DisplayName("empty owner + RM + SC → deny (admin-only)")
    fun `empty roles denied for editor`() {
        loginAs(editor("bob"))
        val id = UUID.randomUUID()
        stub(id, owner = null)
        assertFalse(evaluator.canEditComponent(id.toString()))
    }

    @Test
    @DisplayName("blank owner string is treated as no-owner → deny")
    fun `blank owner treated as empty`() {
        loginAs(editor("bob"))
        val id = UUID.randomUUID()
        stub(id, owner = "   ")
        assertFalse(evaluator.canEditComponent(id.toString()))
    }

    @Test
    @DisplayName("unknown UUID (no owner/RM/SC) → deny")
    fun `unknown id denied`() {
        loginAs(editor("bob"))
        val id = UUID.randomUUID()
        stub(id) // all empty/null
        assertFalse(evaluator.canEditComponent(id.toString()))
    }

    @Test
    @DisplayName("non-UUID path resolves via component key, then membership → allow")
    fun `component key fallback`() {
        loginAs(editor("bob"))
        val id = UUID.randomUUID()
        whenMock(repo.findByComponentKey("my-component")).thenReturn(ComponentEntity(id = id))
        stub(id, owner = "bob")
        assertTrue(evaluator.canEditComponent("my-component"))
    }

    @Test
    @DisplayName("non-UUID path with unknown key → deny")
    fun `unknown component key denied`() {
        loginAs(editor("ghostuser"))
        whenMock(repo.findByComponentKey("ghost")).thenReturn(null)
        assertFalse(evaluator.canEditComponent("ghost"))
    }

    @Test
    @DisplayName("no ComponentRepository bean (no-db boot mode) → deny, never throws")
    fun `no repository denied`() {
        @Suppress("UNCHECKED_CAST")
        val emptyProvider = mock(ObjectProvider::class.java) as ObjectProvider<ComponentRepository>
        whenMock(emptyProvider.getIfAvailable()).thenReturn(null)
        val noDbEvaluator = PermissionEvaluator(sec, emptyProvider)
        loginAs(editor("bob"))
        assertFalse(noDbEvaluator.canEditComponent(UUID.randomUUID().toString()))
    }

    @Test
    @DisplayName("anonymous username → deny even when listed as the component owner")
    fun `anonymous username denied`() {
        loginAs(
            User("anonymous", setOf(Role("ROLE_ANONYMOUS", setOf("ACCESS_COMPONENTS"))), emptySet()),
        )
        val id = UUID.randomUUID()
        assertFalse(evaluator.canEditComponent(id.toString()))
        verify(repo, never()).findComponentOwnerById(id)
    }
}
