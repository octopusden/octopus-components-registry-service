package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.config.ArchiveReadinessProperties
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import org.octopusden.octopus.infrastructure.jira.JiraClient
import org.octopusden.octopus.infrastructure.jira.dto.Project
import org.octopusden.octopus.infrastructure.jira.dto.ProjectCategory
import java.util.UUID

class JiraProjectCheckerTest {
    private val jiraClient = mock<JiraClient>()
    private val sharingHelper = mock<SharingHelper>()
    private val props = ArchiveReadinessProperties(retiredJiraProjectCategories = setOf("X Archive"))
    private val checker = JiraProjectChecker(jiraClient, sharingHelper, props)
    private val componentId = UUID.randomUUID()

    @Test
    fun `category in retired set yields PASSED with no classification`() {
        whenever(jiraClient.getProject("PROJ")).thenReturn(Project("PROJ", ProjectCategory("X Archive")))
        whenever(sharingHelper.sharedWithForJiraProject(any(), eq(componentId))).thenReturn(emptyList())
        val result = checker.checkProject("PROJ", componentId)
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `category not retired yields FAILED with no classification`() {
        whenever(jiraClient.getProject("PROJ")).thenReturn(Project("PROJ", ProjectCategory("Development")))
        whenever(sharingHelper.sharedWithForJiraProject(any(), eq(componentId))).thenReturn(emptyList())
        val result = checker.checkProject("PROJ", componentId)
        assertThat(result.outcome).isEqualTo(Outcome.FAILED)
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `null category yields FAILED, not a spurious retired match`() {
        whenever(jiraClient.getProject("PROJ")).thenReturn(Project("PROJ", null))
        whenever(sharingHelper.sharedWithForJiraProject(any(), eq(componentId))).thenReturn(emptyList())
        assertThat(checker.checkProject("PROJ", componentId).outcome).isEqualTo(Outcome.FAILED)
    }

    @Test
    fun `shared with live component yields PASSED regardless of category`() {
        whenever(jiraClient.getProject("PROJ")).thenReturn(Project("PROJ", ProjectCategory("Development")))
        whenever(sharingHelper.sharedWithForJiraProject(any(), eq(componentId))).thenReturn(listOf("other-comp"))
        val result = checker.checkProject("PROJ", componentId)
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.sharedWith).containsExactly("other-comp")
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `no retired categories configured yields UNKNOWN classified NOT_CONFIGURED`() {
        val noCatChecker = JiraProjectChecker(
            jiraClient,
            sharingHelper,
            ArchiveReadinessProperties(retiredJiraProjectCategories = emptySet()),
        )
        whenever(jiraClient.getProject("PROJ")).thenReturn(Project("PROJ", ProjectCategory("Development")))
        val result = noCatChecker.checkProject("PROJ", componentId)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.NOT_CONFIGURED)
    }

    @Test
    fun `jira client not configured yields UNKNOWN classified NOT_CONFIGURED`() {
        val noClientChecker = JiraProjectChecker(null, sharingHelper, props)
        val result = noClientChecker.checkProject("PROJ", componentId)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.NOT_CONFIGURED)
    }

    @Test
    fun `jira project read failure yields UNKNOWN classified SYSTEM_UNAVAILABLE`() {
        whenever(jiraClient.getProject("PROJ")).thenThrow(RuntimeException("boom"))
        val result = checker.checkProject("PROJ", componentId)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
    }
}
