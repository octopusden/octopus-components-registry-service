package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.VcsSettingsEntryRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.util.VcsUrlCanonicalizer
import java.util.UUID

class SharingHelperTest {
    private val versionLineRepo = mock<VersionLineRepository>()
    private val componentRepo = mock<ComponentRepository>()
    private val vcsRepo = mock<VcsSettingsEntryRepository>()
    private val configRepo = mock<ComponentConfigurationRepository>()
    private val helper = SharingHelper(versionLineRepo, componentRepo, vcsRepo, configRepo)

    private fun liveComponent(
        name: String,
        id: UUID = UUID.randomUUID(),
    ) = ComponentEntity(id = id, componentKey = name, archived = false)

    private fun archivedComponent(
        name: String,
        id: UUID = UUID.randomUUID(),
    ) = ComponentEntity(id = id, componentKey = name, archived = true)

    @Test
    fun `sharedWithForRepo returns empty when no other component uses the repo`() {
        whenever(componentRepo.findAll()).thenReturn(emptyList())
        val result = helper.sharedWithForRepo(
            VcsUrlCanonicalizer.canonicalize("ssh://git.example.com/only-repo.git"),
            UUID.randomUUID(),
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `sharedWithForRepo matches components recording the same repo via canonically-equivalent but textually different URLs`() {
        // Proves the I1 canonicalizer fix (userinfo stripping in the "://"-scheme branch) works
        // end-to-end through SharingHelper, not just in VcsUrlCanonicalizer isolation: the
        // sharing component records the repo via SCP-style shorthand, the checked component's
        // own URL (below, fed into canonicalize()) uses an explicit "ssh://" scheme with
        // userinfo — the same repository, two different textual forms.
        val sharingId = UUID.randomUUID()
        val sharingComp = liveComponent("sharing-comp", sharingId)
        val cfg = ComponentConfigurationEntity(id = UUID.randomUUID(), component = sharingComp, rowType = "BASE")
        val vcsEntry = VcsSettingsEntryEntity(
            id = UUID.randomUUID(),
            componentConfiguration = cfg,
            name = "main",
            vcsPath = "git@git.example.com:owner/repo.git",
        )
        whenever(componentRepo.findAll()).thenReturn(listOf(sharingComp))
        whenever(configRepo.findByComponentId(sharingId)).thenReturn(listOf(cfg))
        whenever(vcsRepo.findByComponentConfigurationId(cfg.id!!)).thenReturn(listOf(vcsEntry))

        val result = helper.sharedWithForRepo(
            VcsUrlCanonicalizer.canonicalize("ssh://git@git.example.com/owner/repo.git"),
            UUID.randomUUID(),
        )

        assertThat(result).containsExactly("sharing-comp")
    }

    @Test
    fun `sharedWithForTcProject returns component whose version line matches project id`() {
        val tcProjectId = "TC_PROJECT_1"
        val otherId = UUID.randomUUID()
        val checkedId = UUID.randomUUID()
        val otherComp = liveComponent("other-comp", otherId)
        val tcProject = TeamcityProjectEntity(projectId = tcProjectId)
        val versionLine = VersionLineEntity(component = otherComp, teamcityProject = tcProject)
        whenever(versionLineRepo.findDistinctLinkedProjectIds()).thenReturn(listOf(tcProjectId))
        whenever(versionLineRepo.findByProjectIdsWithComponent(listOf(tcProjectId))).thenReturn(listOf(versionLine))
        val result = helper.sharedWithForTcProject(tcProjectId, emptySet(), checkedId)
        assertThat(result).containsExactly("other-comp")
    }

    @Test
    fun `sharedWithForTcProject detects sharing via descendant project id`() {
        val parentId = "TC_PARENT"
        val descendantId = "TC_CHILD_NOT_MATCHING_PREFIX"
        val otherId = UUID.randomUUID()
        val checkedId = UUID.randomUUID()
        val otherComp = liveComponent("descendant-user", otherId)
        val tcProject = TeamcityProjectEntity(projectId = descendantId)
        val versionLine = VersionLineEntity(component = otherComp, teamcityProject = tcProject)
        whenever(versionLineRepo.findDistinctLinkedProjectIds()).thenReturn(listOf(descendantId))
        whenever(versionLineRepo.findByProjectIdsWithComponent(listOf(descendantId))).thenReturn(listOf(versionLine))
        val result = helper.sharedWithForTcProject(parentId, setOf(descendantId), checkedId)
        assertThat(result).containsExactly("descendant-user")
    }

    @Test
    fun `sharedWithForTcProject excludes archived components`() {
        val projectId = "TC_PROJECT_X"
        val archivedComp = archivedComponent("archived-comp")
        val tcProject = TeamcityProjectEntity(projectId = projectId)
        val versionLine = VersionLineEntity(component = archivedComp, teamcityProject = tcProject)
        whenever(versionLineRepo.findDistinctLinkedProjectIds()).thenReturn(listOf(projectId))
        whenever(versionLineRepo.findByProjectIdsWithComponent(listOf(projectId))).thenReturn(listOf(versionLine))
        val result = helper.sharedWithForTcProject(projectId, emptySet(), UUID.randomUUID())
        assertThat(result).isEmpty()
    }

    @Test
    fun `sharedWithForTcProject excludes the checked component`() {
        val projectId = "TC_PROJECT_SELF"
        val checkedId = UUID.randomUUID()
        val checkedComp = liveComponent("checked-comp", checkedId)
        val tcProject = TeamcityProjectEntity(projectId = projectId)
        val versionLine = VersionLineEntity(component = checkedComp, teamcityProject = tcProject)
        whenever(versionLineRepo.findDistinctLinkedProjectIds()).thenReturn(listOf(projectId))
        whenever(versionLineRepo.findByProjectIdsWithComponent(listOf(projectId))).thenReturn(listOf(versionLine))
        val result = helper.sharedWithForTcProject(projectId, emptySet(), checkedId)
        assertThat(result).isEmpty()
    }

    @Test
    fun `intersection runs registry-ids against descendants not the other way`() {
        val registryIds = listOf("MATCH_A", "MATCH_B")
        val descendantIds = (1..1000).map { "DESC_$it" }.toSet() + setOf("MATCH_A")
        whenever(versionLineRepo.findDistinctLinkedProjectIds()).thenReturn(registryIds)
        whenever(versionLineRepo.findByProjectIdsWithComponent(listOf("MATCH_A"))).thenReturn(emptyList())
        helper.sharedWithForTcProject("SOME_PARENT", descendantIds, UUID.randomUUID())
        // The query must be scoped down to the small registry-linked intersection ("MATCH_A"
        // only — "MATCH_B" isn't a descendant), never blown up to (a subset of) the 1000+
        // descendant ids, which is what running the intersection the other way around would risk.
        verify(versionLineRepo).findByProjectIdsWithComponent(listOf("MATCH_A"))
        verify(versionLineRepo, never()).findByProjectIdsWithComponent(argThat { size > 2 })
    }
}
