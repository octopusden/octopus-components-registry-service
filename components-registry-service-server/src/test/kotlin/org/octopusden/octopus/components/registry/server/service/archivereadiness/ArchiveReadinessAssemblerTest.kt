package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.TargetKind
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.VcsSettingsEntryRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import java.util.UUID

class ArchiveReadinessAssemblerTest {
    private val versionLineRepository = mock<VersionLineRepository>()
    private val componentConfigurationRepository = mock<ComponentConfigurationRepository>()
    private val vcsSettingsEntryRepository = mock<VcsSettingsEntryRepository>()
    private val livenessProbe = mock<LivenessProbe>()
    private val repositoryChecker = mock<RepositoryChecker>()
    private val teamcityChecker = mock<TeamcityChecker>()
    private val jiraIssuesChecker = mock<JiraIssuesChecker>()
    private val jiraProjectChecker = mock<JiraProjectChecker>()
    private val pairResolver = mock<JiraEffectivePairResolver>()

    private val assembler = ArchiveReadinessAssembler(
        versionLineRepository,
        componentConfigurationRepository,
        vcsSettingsEntryRepository,
        livenessProbe,
        repositoryChecker,
        teamcityChecker,
        jiraIssuesChecker,
        jiraProjectChecker,
        pairResolver,
    )

    private val componentId = UUID.randomUUID()
    private val component = ComponentEntity(id = componentId, componentKey = "my-comp")

    private val liveSnapshot = LivenessSnapshot(
        vcsConfigured = true,
        vcsLive = true,
        teamcityConfigured = true,
        teamcityLive = true,
        jiraIssuesConfigured = true,
        jiraIssuesLive = true,
        jiraProjectConfigured = true,
        jiraProjectLive = true,
    )

    private fun noTargets() {
        whenever(livenessProbe.probe()).thenReturn(liveSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(pairResolver.pairsFor(componentId)).thenReturn(emptySet())
    }

    private fun versionLine(projectId: String): VersionLineEntity =
        VersionLineEntity(component = component, teamcityProject = TeamcityProjectEntity(projectId = projectId))

    private fun configRowWithVcsEntries(vararg vcsPaths: String): ComponentConfigurationEntity {
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = component, rowType = "BASE")
        val entries = vcsPaths.mapIndexed { index, path ->
            VcsSettingsEntryEntity(componentConfiguration = row, name = "vcs-$index", vcsPath = path)
        }
        whenever(vcsSettingsEntryRepository.findByComponentConfigurationId(row.id!!)).thenReturn(entries)
        return row
    }

    @Test
    fun `no targets yields ready with no entries`() {
        noTargets()
        val response = assembler.assemble(component)
        assertThat(response.ready).isTrue()
        assertThat(response.entries).isEmpty()
    }

    @Test
    fun `all PASSED yields ready`() {
        whenever(livenessProbe.probe()).thenReturn(liveSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(listOf(versionLine("TC1")))
        val row = configRowWithVcsEntries("ssh://git.example.com/repo.git")
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(listOf(row))
        whenever(pairResolver.pairsFor(componentId)).thenReturn(setOf("PROJ" to null))
        whenever(teamcityChecker.check(any())).thenReturn(CheckResult(Outcome.PASSED))
        whenever(repositoryChecker.check(any())).thenReturn(CheckResult(Outcome.PASSED))
        whenever(jiraIssuesChecker.checkPair("PROJ", null, componentId)).thenReturn(CheckResult(Outcome.PASSED))
        whenever(jiraProjectChecker.checkProject("PROJ", componentId)).thenReturn(CheckResult(Outcome.PASSED))

        val response = assembler.assemble(component)

        assertThat(response.ready).isTrue()
        assertThat(response.entries).hasSize(4)
        assertThat(response.entries).allMatch { it.outcome == Outcome.PASSED }
    }

    @Test
    fun `one FAILED entry makes response not ready`() {
        whenever(livenessProbe.probe()).thenReturn(liveSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(listOf(versionLine("TC1")))
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(pairResolver.pairsFor(componentId)).thenReturn(emptySet())
        whenever(teamcityChecker.check(any())).thenReturn(CheckResult(Outcome.FAILED))

        val response = assembler.assemble(component)

        assertThat(response.ready).isFalse()
        assertThat(response.entries).hasSize(1)
        assertThat(response.entries[0].outcome).isEqualTo(Outcome.FAILED)
    }

    @Test
    fun `one UNKNOWN entry makes response not ready`() {
        whenever(livenessProbe.probe()).thenReturn(liveSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(listOf(versionLine("TC1")))
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(pairResolver.pairsFor(componentId)).thenReturn(emptySet())
        whenever(teamcityChecker.check(any())).thenReturn(CheckResult(Outcome.UNKNOWN, reason = "TC down"))

        val response = assembler.assemble(component)

        assertThat(response.ready).isFalse()
        assertThat(response.entries).hasSize(1)
        assertThat(response.entries[0].outcome).isEqualTo(Outcome.UNKNOWN)
    }

    @Test
    fun `two version lines on the same TC project yield exactly one entry`() {
        whenever(livenessProbe.probe()).thenReturn(liveSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(
            listOf(versionLine("TC1"), versionLine("TC1")),
        )
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(pairResolver.pairsFor(componentId)).thenReturn(emptySet())
        whenever(teamcityChecker.check(any())).thenReturn(CheckResult(Outcome.PASSED))

        val response = assembler.assemble(component)

        assertThat(response.entries).hasSize(1)
        assertThat(response.entries[0].targetKind).isEqualTo(TargetKind.TEAMCITY_PROJECT)
        assertThat(response.entries[0].targetId).isEqualTo("TC1")
        verify(teamcityChecker, times(1)).check(any())
    }

    @Test
    fun `two VCS entries canonicalizing to the same URL yield exactly one REPOSITORY entry`() {
        whenever(livenessProbe.probe()).thenReturn(liveSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(emptyList())
        // Two distinct raw forms of the same repository: differing scheme, case, and .git suffix.
        val row = configRowWithVcsEntries("ssh://GIT.EXAMPLE.COM/Repo.GIT", "https://git.example.com/Repo")
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(listOf(row))
        whenever(pairResolver.pairsFor(componentId)).thenReturn(emptySet())
        whenever(repositoryChecker.check(any())).thenReturn(CheckResult(Outcome.PASSED))

        val response = assembler.assemble(component)

        assertThat(response.entries).hasSize(1)
        assertThat(response.entries[0].targetKind).isEqualTo(TargetKind.REPOSITORY)
        // The RAW first-seen URL is the target identity, not the canonical form.
        assertThat(response.entries[0].targetId).isEqualTo("ssh://GIT.EXAMPLE.COM/Repo.GIT")
        verify(repositoryChecker, times(1)).check(any())
    }

    @Test
    fun `TeamCity liveness down yields UNKNOWN for every target with one shared reason, checker never called`() {
        val downSnapshot = liveSnapshot.copy(teamcityConfigured = true, teamcityLive = false)
        whenever(livenessProbe.probe()).thenReturn(downSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(
            listOf(versionLine("TC1"), versionLine("TC2")),
        )
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(pairResolver.pairsFor(componentId)).thenReturn(emptySet())

        val response = assembler.assemble(component)

        assertThat(response.entries).hasSize(2)
        assertThat(response.entries).allMatch { it.outcome == Outcome.UNKNOWN }
        assertThat(response.entries[0].reason).isEqualTo(response.entries[1].reason)
        assertThat(response.entries[0].reason).isNotNull()
        verify(teamcityChecker, never()).check(any())
    }

    @Test
    fun `Jira not configured yields no JIRA_ISSUES or JIRA_PROJECT entries at all`() {
        val unconfiguredSnapshot = liveSnapshot.copy(
            jiraIssuesConfigured = false,
            jiraIssuesLive = false,
            jiraProjectConfigured = false,
            jiraProjectLive = false,
        )
        whenever(livenessProbe.probe()).thenReturn(unconfiguredSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(pairResolver.pairsFor(componentId)).thenReturn(setOf("PROJ" to null))

        val response = assembler.assemble(component)

        assertThat(response.entries).isEmpty()
        assertThat(response.ready).isTrue()
        verify(jiraIssuesChecker, never()).checkPair(any(), anyOrNull(), any())
        verify(jiraProjectChecker, never()).checkProject(any(), any())
    }

    @Test
    fun `two pairs on distinct project keys yield 4 Jira entries`() {
        whenever(livenessProbe.probe()).thenReturn(liveSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(pairResolver.pairsFor(componentId)).thenReturn(setOf("PROJ1" to null, "PROJ2" to "PREFIX"))
        whenever(jiraIssuesChecker.checkPair(any(), anyOrNull(), any())).thenReturn(CheckResult(Outcome.PASSED))
        whenever(jiraProjectChecker.checkProject(any(), any())).thenReturn(CheckResult(Outcome.PASSED))

        val response = assembler.assemble(component)

        val issuesEntries = response.entries.filter { it.targetKind == TargetKind.JIRA_ISSUES }
        val projectEntries = response.entries.filter { it.targetKind == TargetKind.JIRA_PROJECT }
        assertThat(response.entries).hasSize(4)
        assertThat(issuesEntries.map { it.targetId }).containsExactlyInAnyOrder("PROJ1", "PROJ2:PREFIX")
        assertThat(projectEntries.map { it.targetId }).containsExactlyInAnyOrder("PROJ1", "PROJ2")
    }

    @Test
    fun `two pairs sharing one project key yield 3 Jira entries, JIRA_PROJECT deduped`() {
        whenever(livenessProbe.probe()).thenReturn(liveSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(pairResolver.pairsFor(componentId)).thenReturn(setOf("PROJ1" to "A", "PROJ1" to "B"))
        whenever(jiraIssuesChecker.checkPair(any(), anyOrNull(), any())).thenReturn(CheckResult(Outcome.PASSED))
        whenever(jiraProjectChecker.checkProject(any(), any())).thenReturn(CheckResult(Outcome.PASSED))

        val response = assembler.assemble(component)

        val issuesEntries = response.entries.filter { it.targetKind == TargetKind.JIRA_ISSUES }
        val projectEntries = response.entries.filter { it.targetKind == TargetKind.JIRA_PROJECT }
        assertThat(response.entries).hasSize(3)
        assertThat(issuesEntries.map { it.targetId }).containsExactlyInAnyOrder("PROJ1:A", "PROJ1:B")
        assertThat(projectEntries.map { it.targetId }).containsExactly("PROJ1")
        verify(jiraProjectChecker, times(1)).checkProject("PROJ1", componentId)
    }

    @Test
    fun `JIRA_ISSUES entries always carry empty sharedWith even if the checker result populated it`() {
        whenever(livenessProbe.probe()).thenReturn(liveSnapshot)
        whenever(versionLineRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(componentConfigurationRepository.findByComponentId(componentId)).thenReturn(emptyList())
        whenever(pairResolver.pairsFor(componentId)).thenReturn(setOf("PROJ" to null))
        whenever(jiraIssuesChecker.checkPair("PROJ", null, componentId))
            .thenReturn(CheckResult(Outcome.PASSED, sharedWith = listOf("should-be-dropped")))
        whenever(jiraProjectChecker.checkProject("PROJ", componentId)).thenReturn(CheckResult(Outcome.PASSED))

        val response = assembler.assemble(component)

        val issuesEntry = response.entries.single { it.targetKind == TargetKind.JIRA_ISSUES }
        assertThat(issuesEntry.sharedWith).isEmpty()
    }
}
