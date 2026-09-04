package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import org.octopusden.octopus.components.registry.server.jira.JiraFixVersionRef
import org.octopusden.octopus.components.registry.server.jira.JiraIssueSearchClient
import org.octopusden.octopus.components.registry.server.jira.JiraSearchIssue
import org.octopusden.octopus.components.registry.server.jira.JiraSearchIssueFields
import org.octopusden.octopus.components.registry.server.jira.JiraSearchResponse
import java.util.UUID

class JiraIssuesCheckerTest {
    private val jiraSearchClient = mock<JiraIssueSearchClient>()
    private val pairResolver = mock<JiraEffectivePairResolver>()
    private val checker = JiraIssuesChecker(jiraSearchClient, pairResolver)

    init {
        whenever(pairResolver.hasNullPrefixConflict(any())).thenReturn(false)
    }

    // Mirrors JiraIssuesChecker's own private PAGE_SIZE — kept in lockstep manually since the
    // production constant is private to that file.
    private val pageSize = 50

    private fun issue(
        key: String,
        summary: String,
        fixVersionNames: List<String>,
    ) = JiraSearchIssue(key, JiraSearchIssueFields(summary, fixVersionNames.map { JiraFixVersionRef(it) }))

    private fun mockSearch(issues: List<JiraSearchIssue>) {
        whenever(jiraSearchClient.searchJql(any(), any(), any())).thenReturn(JiraSearchResponse(issues.size, issues))
    }

    /**
     * Answers [JiraIssueSearchClient.searchJql] per-call based on the requested `startAt`,
     * simulating a Jira project with [total] open issues where every page is full except possibly
     * the last. A single shared non-matching issue is reused across every page to avoid building
     * one object per issue when [total] is large (the MAX_PAGES backstop test needs thousands).
     * A page containing [matchOnPage] (0-indexed) gets one matching issue as its first element.
     */
    private fun mockPagedSearch(
        total: Int,
        matchOnPage: Int? = null,
    ) {
        val nonMatching = issue("PROJ-FILLER", "unrelated version", listOf("2.0.0"))
        val matching = issue("PROJ-MATCH", "matching version", listOf("1.5"))
        whenever(jiraSearchClient.searchJql(any(), any(), any())).thenAnswer { invocation ->
            val startAt = invocation.getArgument<Int>(1)
            val size = (total - startAt).coerceIn(0, pageSize)
            val pageIndex = startAt / pageSize
            val issues = List(size) { i -> if (matchOnPage == pageIndex && i == 0) matching else nonMatching }
            JiraSearchResponse(total, issues)
        }
    }

    @Test
    fun `prefixed pair — matching issue yields NOT_COMPLETED with no classification`() {
        mockSearch(listOf(issue("PROJ-1", "some issue", listOf("1.2.3"))))
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.NOT_COMPLETED)
        assertThat(result.sharedWith).isEmpty()
        assertThat(result.openIssues).extracting("key").containsExactly("PROJ-1")
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `null prefix, no conflict, sole claim — bare version counts`() {
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(false)
        whenever(pairResolver.hasOtherPairOnProjectKey("PROJ")).thenReturn(false)
        mockSearch(listOf(issue("PROJ-2", "bare issue", listOf("1.2"))))
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.NOT_COMPLETED)
        assertThat(result.openIssues).extracting("key").containsExactly("PROJ-2")
    }

    @Test
    fun `null prefix — issue with only a prefixed version is not counted when a sibling pair claims the project`() {
        // A sibling (prefixed) pair claims this project key too, so this null-prefix pair's
        // scope is bare-version-pattern issues only — the prefixed one belongs to the sibling.
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(false)
        whenever(pairResolver.hasOtherPairOnProjectKey("PROJ")).thenReturn(true)
        mockSearch(listOf(issue("PROJ-3", "prefixed issue", listOf("REL-1.2"))))
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.COMPLETED)
        assertThat(result.openIssues).isEmpty()
    }

    @Test
    fun `null prefix — sole claim on the project counts every open issue, bare or not`() {
        // No other pair (of any prefix) claims this project key, so this pair's scope is the
        // WHOLE project — nothing excuses a non-bare-version issue when there is no sibling to
        // exclude it in favor of (spec.md "A sole claim on a project is scoped by the whole project").
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(false)
        whenever(pairResolver.hasOtherPairOnProjectKey("PROJ")).thenReturn(false)
        mockSearch(listOf(issue("PROJ-4", "non-bare version issue", listOf("v1.2.3"))))
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.NOT_COMPLETED)
        assertThat(result.openIssues).extracting("key").containsExactly("PROJ-4")
    }

    @Test
    fun `prefixed pair — issue whose fixVersion does not start with the prefix is not counted`() {
        mockSearch(listOf(issue("PROJ-5", "unrelated version", listOf("2.0.0"))))
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.COMPLETED)
        assertThat(result.openIssues).isEmpty()
    }

    @Test
    fun `JQL sent to searchJql never contains a fixVersion clause`() {
        mockSearch(emptyList())
        checker.checkPair("PROJ", "1.", UUID.randomUUID())
        val jqlCaptor = argumentCaptor<String>()
        verify(jiraSearchClient).searchJql(jqlCaptor.capture(), any(), any())
        assertThat(jqlCaptor.firstValue).isEqualTo("project = \"PROJ\" AND statusCategory != Done")
        assertThat(jqlCaptor.firstValue).doesNotContain("fixVersion")
    }

    @Test
    fun `null prefix with a registry conflict yields UNKNOWN classified REGISTRY_DATA without searching`() {
        whenever(pairResolver.hasNullPrefixConflict("PROJ")).thenReturn(true)
        val result = checker.checkPair("PROJ", null, UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.REGISTRY_DATA)
        verify(jiraSearchClient, never()).searchJql(any(), any(), any())
    }

    @Test
    fun `no open issue yields COMPLETED with no classification`() {
        mockSearch(emptyList())
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.COMPLETED)
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `a truncated first page with no match on it reads the next page instead of stopping at UNKNOWN`() {
        // Jira reports more open issues (80) than a single page (50) holds, none matching on
        // page 1 — the fix for the pagination bug: the checker must read page 2 (the remaining
        // 30) rather than giving up with UNKNOWN after only the first page.
        mockPagedSearch(total = 80)
        val outcome = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(outcome.outcome).isEqualTo(Outcome.COMPLETED)
        assertThat(outcome.openIssues).isEmpty()
        verify(jiraSearchClient, times(2)).searchJql(any(), any(), any())
    }

    @Test
    fun `a match on a later page yields NOT_COMPLETED without reading further pages`() {
        // A match on page 2 (of 3 possible pages, total=120) is trustworthy evidence on its own —
        // more open issues on page 3 would only reinforce NOT_COMPLETED, so it must not be read.
        mockPagedSearch(total = 120, matchOnPage = 1)
        val outcome = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(outcome.outcome).isEqualTo(Outcome.NOT_COMPLETED)
        assertThat(outcome.openIssues).extracting("key").containsExactly("PROJ-MATCH")
        verify(jiraSearchClient, times(2)).searchJql(any(), any(), any())
    }

    @Test
    fun `a project with more open issues than the page backstop will read yields UNKNOWN, not a false COMPLETED`() {
        // A project so large the checker would never finish paging through it (or a Jira whose
        // `total` field is unstable/wrong) must fail closed rather than loop forever or claim
        // COMPLETED without having read every issue.
        mockPagedSearch(total = Int.MAX_VALUE)
        val outcome = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(outcome.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(outcome.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
        assertThat(outcome.openIssues).isEmpty()
    }

    @Test
    fun `search failure yields UNKNOWN classified SYSTEM_UNAVAILABLE`() {
        whenever(jiraSearchClient.searchJql(any(), any(), any())).thenThrow(RuntimeException("boom"))
        val result = checker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
    }

    @Test
    fun `jira search client not configured yields UNKNOWN classified NOT_CONFIGURED`() {
        val noClientChecker = JiraIssuesChecker(null, pairResolver)
        val result = noClientChecker.checkPair("PROJ", "1.", UUID.randomUUID())
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.NOT_CONFIGURED)
    }
}
