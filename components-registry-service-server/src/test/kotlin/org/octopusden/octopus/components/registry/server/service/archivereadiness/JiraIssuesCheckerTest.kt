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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
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
        whenever(searchClient.searchJql(any(), any(), any(), anyOrNull())).thenReturn(Promises.promise(result))
    }

    @Test
    fun `prefixed pair — matching issue yields FAILED with no classification`() {
        mockSearch(listOf(mockIssue("PROJ-1", "some issue", listOf("1.2.3"))))
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.FAILED)
        assertThat(result.sharedWith).isEmpty()
        assertThat(result.openIssues).extracting("key").containsExactly("PROJ-1")
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `null prefix, no conflict, sole claim — bare version counts`() {
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(false)
        whenever(pairResolver.hasOtherPairOnProjectKey("PROJ")).thenReturn(false)
        mockSearch(listOf(mockIssue("PROJ-2", "bare issue", listOf("1.2"))))
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.FAILED)
        assertThat(result.openIssues).extracting("key").containsExactly("PROJ-2")
    }

    @Test
    fun `null prefix — issue with only a prefixed version is not counted when a sibling pair claims the project`() {
        // A sibling (prefixed) pair claims this project key too, so this null-prefix pair's
        // scope is bare-version-pattern issues only — the prefixed one belongs to the sibling.
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(false)
        whenever(pairResolver.hasOtherPairOnProjectKey("PROJ")).thenReturn(true)
        mockSearch(listOf(mockIssue("PROJ-3", "prefixed issue", listOf("REL-1.2"))))
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.openIssues).isEmpty()
    }

    @Test
    fun `null prefix — sole claim on the project counts every open issue, bare or not`() {
        // No other pair (of any prefix) claims this project key, so this pair's scope is the
        // WHOLE project — nothing excuses a non-bare-version issue when there is no sibling to
        // exclude it in favor of (spec.md "A sole claim on a project is scoped by the whole project").
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(false)
        whenever(pairResolver.hasOtherPairOnProjectKey("PROJ")).thenReturn(false)
        mockSearch(listOf(mockIssue("PROJ-4", "non-bare version issue", listOf("v1.2.3"))))
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.FAILED)
        assertThat(result.openIssues).extracting("key").containsExactly("PROJ-4")
    }

    @Test
    fun `prefixed pair — issue whose fixVersion does not start with the prefix is not counted`() {
        mockSearch(listOf(mockIssue("PROJ-5", "unrelated version", listOf("2.0.0"))))
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.openIssues).isEmpty()
    }

    @Test
    fun `JQL sent to searchJql never contains a fixVersion clause`() {
        mockSearch(emptyList())
        checker.checkPair("PROJ", "1.", UUID.randomUUID())
        val jqlCaptor = argumentCaptor<String>()
        verify(searchClient).searchJql(jqlCaptor.capture(), any(), any(), anyOrNull())
        assertThat(jqlCaptor.firstValue).isEqualTo("project = \"PROJ\" AND statusCategory != Done")
        assertThat(jqlCaptor.firstValue).doesNotContain("fixVersion")
    }

    @Test
    fun `searchJql requests no explicit fields, leaving Jira's own default field set in place`() {
        // A null fields set makes the client omit the "fields" query param entirely rather than
        // restrict to an explicit allowlist — the allowlist approach broke in production every
        // time it omitted a field jira-rest-java-client-core's own parser reads unconditionally
        // ("issuetype", then "created"), since Jira only returns fields that were requested.
        mockSearch(emptyList())
        checker.checkPair("PROJ", "1.", UUID.randomUUID())
        verify(searchClient).searchJql(any(), any(), any(), isNull())
    }

    @Test
    fun `null prefix with a registry conflict yields UNKNOWN classified REGISTRY_DATA without searching`() {
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(true)
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.REGISTRY_DATA)
        verify(searchClient, never()).searchJql(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `no open issue yields PASSED with no classification`() {
        mockSearch(emptyList())
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `a truncated page with no match on it yields UNKNOWN, not a false PASSED`() {
        // Jira reports more open issues (80) than this check fetched (2, none matching) — the
        // one matching issue, if any, could be sitting unread beyond the fetched page. An empty
        // match on a truncated page is not trustworthy evidence of PASSED.
        val page = listOf(mockIssue("PROJ-6", "unrelated version", listOf("2.0.0")))
        val result = SearchResult(0, 50, 80, page)
        whenever(searchClient.searchJql(any(), any(), any(), anyOrNull())).thenReturn(Promises.promise(result))
        val outcome = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(outcome.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(outcome.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
        assertThat(outcome.openIssues).isEmpty()
    }

    @Test
    fun `a truncated page with a match on it still yields FAILED — truncation only matters for PASSED`() {
        // More open issues exist beyond the fetched page, but a match was already found on this
        // page — additional unseen issues would only reinforce FAILED, never overturn it.
        val page = listOf(mockIssue("PROJ-7", "matching", listOf("1.5")))
        val result = SearchResult(0, 50, 80, page)
        whenever(searchClient.searchJql(any(), any(), any(), anyOrNull())).thenReturn(Promises.promise(result))
        val outcome = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(outcome.outcome).isEqualTo(Outcome.FAILED)
        assertThat(outcome.openIssues).extracting("key").containsExactly("PROJ-7")
    }

    @Test
    fun `search failure yields UNKNOWN classified SYSTEM_UNAVAILABLE`() {
        whenever(searchClient.searchJql(any(), any(), any(), anyOrNull())).thenThrow(RuntimeException("boom"))
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
    }

    @Test
    fun `jira rest client not configured yields UNKNOWN classified NOT_CONFIGURED`() {
        val noClientChecker = JiraIssuesChecker(null, pairResolver)
        val result = noClientChecker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.NOT_CONFIGURED)
    }
}
