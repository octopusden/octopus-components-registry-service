package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.config.ArchiveReadinessProperties
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
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
    fun `category in retired set yields PASSED`() {
        whenever(jiraClient.getProject("PROJ")).thenReturn(Project("PROJ", ProjectCategory("X Archive")))
        whenever(sharingHelper.sharedWithForJiraProject(any(), eq(componentId))).thenReturn(emptyList())
        assertThat(checker.checkProject("PROJ", componentId).outcome).isEqualTo(Outcome.PASSED)
    }

    @Test
    fun `category not retired yields FAILED`() {
        whenever(jiraClient.getProject("PROJ")).thenReturn(Project("PROJ", ProjectCategory("Development")))
        whenever(sharingHelper.sharedWithForJiraProject(any(), eq(componentId))).thenReturn(emptyList())
        assertThat(checker.checkProject("PROJ", componentId).outcome).isEqualTo(Outcome.FAILED)
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
    }

    @Test
    fun `no retired categories configured yields UNKNOWN`() {
        val noCatChecker = JiraProjectChecker(
            jiraClient,
            sharingHelper,
            ArchiveReadinessProperties(retiredJiraProjectCategories = emptySet()),
        )
        whenever(jiraClient.getProject("PROJ")).thenReturn(Project("PROJ", ProjectCategory("Development")))
        assertThat(noCatChecker.checkProject("PROJ", componentId).outcome).isEqualTo(Outcome.UNKNOWN)
    }

    @Test
    fun `jira client not configured yields UNKNOWN`() {
        val noClientChecker = JiraProjectChecker(null, sharingHelper, props)
        assertThat(noClientChecker.checkProject("PROJ", componentId).outcome).isEqualTo(Outcome.UNKNOWN)
    }

    @Test
    fun `jira project read failure yields UNKNOWN`() {
        whenever(jiraClient.getProject("PROJ")).thenThrow(RuntimeException("boom"))
        assertThat(checker.checkProject("PROJ", componentId).outcome).isEqualTo(Outcome.UNKNOWN)
    }
}
