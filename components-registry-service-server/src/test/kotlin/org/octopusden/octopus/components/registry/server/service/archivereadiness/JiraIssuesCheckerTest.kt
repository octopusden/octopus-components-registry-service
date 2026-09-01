package org.octopusden.octopus.components.registry.server.service.archivereadiness

import com.atlassian.jira.rest.client.api.JiraRestClient
import com.atlassian.jira.rest.client.api.SearchRestClient
import com.atlassian.jira.rest.client.api.domain.Issue
import com.atlassian.jira.rest.client.api.domain.SearchResult
import com.atlassian.jira.rest.client.api.domain.Version
import io.atlassian.util.concurrent.Promises
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import java.util.UUID

class JiraIssuesCheckerTest {
    private val jiraRestClient = mock<JiraRestClient>()
    private val searchClient = mock<SearchRestClient>()
    private val pairResolver = mock<JiraEffectivePairResolver>()
    private val checker = JiraIssuesChecker(jiraRestClient, pairResolver)

    init {
        whenever(jiraRestClient.searchClient).thenReturn(searchClient)
        whenever(pairResolver.hasNullPrefixConflict(any())).thenReturn(false)
    }

    private fun mockIssue(
        key: String,
        summary: String,
        fixVersionNames: List<String>,
    ): Issue {
        val issue = mock<Issue>()
        whenever(issue.key).thenReturn(key)
        whenever(issue.summary).thenReturn(summary)
        val versions = fixVersionNames.map { name ->
            val v = mock<Version>()
            whenever(v.name).thenReturn(name)
            v
        }
        whenever(issue.fixVersions).thenReturn(versions)
        return issue
    }

    private fun mockSearch(issues: List<Issue>) {
        val result = SearchResult(0, 50, issues.size, issues)
        whenever(searchClient.searchJql(any(), any(), any(), any())).thenReturn(Promises.promise(result))
    }

    @Test
    fun `prefixed pair — matching issue yields FAILED`() {
        mockSearch(listOf(mockIssue("PROJ-1", "some issue", listOf("1.2.3"))))
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.FAILED)
        assertThat(result.sharedWith).isEmpty()
        assertThat(result.openIssues).extracting("key").containsExactly("PROJ-1")
    }

    @Test
    fun `null prefix, no conflict, sole claim — bare version counts`() {
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(false)
        mockSearch(listOf(mockIssue("PROJ-2", "bare issue", listOf("1.2"))))
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.FAILED)
        assertThat(result.openIssues).extracting("key").containsExactly("PROJ-2")
    }

    @Test
    fun `null prefix — issue with only a prefixed version is not counted`() {
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(false)
        mockSearch(listOf(mockIssue("PROJ-3", "prefixed issue", listOf("REL-1.2"))))
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.openIssues).isEmpty()
    }

    @Test
    fun `null prefix with a registry conflict yields UNKNOWN without searching`() {
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(true)
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        verify(searchClient, never()).searchJql(any(), any(), any(), any())
    }

    @Test
    fun `no open issue yields PASSED`() {
        mockSearch(emptyList())
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
    }

    @Test
    fun `search failure yields UNKNOWN`() {
        whenever(searchClient.searchJql(any(), any(), any(), any())).thenThrow(RuntimeException("boom"))
        assertThat(checker.checkPair("PROJ", "1.", UUID.randomUUID()).outcome).isEqualTo(Outcome.UNKNOWN)
    }

    @Test
    fun `jira rest client not configured yields UNKNOWN`() {
        val noClientChecker = JiraIssuesChecker(null, pairResolver)
        val result = noClientChecker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
    }
}
