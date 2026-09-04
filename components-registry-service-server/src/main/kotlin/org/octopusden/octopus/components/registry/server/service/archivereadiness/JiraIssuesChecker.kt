package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.JiraIssueRef
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import org.octopusden.octopus.components.registry.server.jira.JiraIssueSearchClient
import org.octopusden.octopus.components.registry.server.jira.JiraSearchIssue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

private val BARE_VERSION = Regex("^\\d")
private const val PAGE_SIZE = 50
private const val MAX_PAGES = 200

// Depends transitively on JPA repos via JiraEffectivePairResolver — drop in no-db mode too.
@ConditionalOnDatabaseEnabled
@Service
class JiraIssuesChecker(
    private val jiraSearchClient: JiraIssueSearchClient?,
    private val pairResolver: JiraEffectivePairResolver,
) {
    private val log = LoggerFactory.getLogger(JiraIssuesChecker::class.java)

    // sharedWith is always empty here (sharing never excuses open issues); componentId is kept
    // only for call-shape symmetry with TargetChecker.
    @Suppress("UnusedParameter", "TooGenericExceptionCaught")
    fun checkPair(
        projectKey: String,
        prefix: String?,
        componentId: UUID,
    ): CheckResult {
        if (jiraSearchClient == null) {
            log.info("JIRA_ISSUES: skipped for {}:{} — Jira issue search not configured", projectKey, prefix)
            return CheckResult(
                Outcome.UNKNOWN,
                reason = "Jira issue search not configured",
                reasonKind = ReasonKind.NOT_CONFIGURED,
            )
        }
        if (prefix == null && pairResolver.hasNullPrefixConflict(projectKey)) {
            log.warn("JIRA_ISSUES: null-prefix conflict on Jira project {} — registry data to correct", projectKey)
            return CheckResult(
                Outcome.UNKNOWN,
                reason = "More than one component claims the default (no-prefix) bucket on Jira " +
                    "project $projectKey — registry data to correct",
                reasonKind = ReasonKind.REGISTRY_DATA,
            )
        }
        // Sole claimant of projectKey (no sibling pair, prefixed or not) -> scope is the whole
        // project, since nothing else could own these issues.
        val wholeProjectScope = prefix == null && !pairResolver.hasOtherPairOnProjectKey(projectKey)
        // fixVersion is a VERSION field: JQL's `~` (CONTAINS) is text-only and Jira rejects it
        // here, so prefix/version matching happens client-side below instead.
        val jql = "project = \"$projectKey\" AND statusCategory != Done"
        return searchOpenIssues(jiraSearchClient, jql, projectKey) { issue ->
            matchesScope(issue, prefix, wholeProjectScope)
        }
    }

    private fun matchesScope(
        issue: JiraSearchIssue,
        prefix: String?,
        wholeProjectScope: Boolean,
    ): Boolean {
        val fixVersionNames = issue.fields.fixVersions.mapNotNull { it.name }
        return when {
            // Client-side prefix match — replaces the invalid JQL "fixVersion ~ prefix*" clause.
            prefix != null -> fixVersionNames.any { it.startsWith(prefix) }
            // Sole claimant on the project: nothing excuses any open issue here, so every
            // issue counts regardless of its fixVersions.
            wholeProjectScope -> true
            // Shares the project key with another (prefixed) pair: only bare-version-
            // pattern issues belong to this null-prefix pair; the rest belong to the sibling.
            else -> fixVersionNames.any { BARE_VERSION.containsMatchIn(it) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun searchOpenIssues(
        client: JiraIssueSearchClient,
        jql: String,
        projectKey: String,
        matches: (JiraSearchIssue) -> Boolean,
    ): CheckResult {
        return try {
            var startAt = 0
            var page = 0
            while (page < MAX_PAGES) {
                val results = client.searchJql(jql, startAt, PAGE_SIZE)
                val issues = results.issues
                val matching = issues.filter(matches)
                if (matching.isNotEmpty()) {
                    val openIssues = matching.map { JiraIssueRef(it.key, it.fields.summary ?: "") }
                    return CheckResult(Outcome.NOT_COMPLETED, openIssues = openIssues)
                }
                startAt += issues.size
                page++
                if (issues.isEmpty() || startAt >= results.total) {
                    return CheckResult(Outcome.COMPLETED)
                }
            }
            log.warn(
                "JIRA_ISSUES: {} has more open issues than the {} pages ({} each) this check will read — cannot confirm none are in scope",
                projectKey,
                MAX_PAGES,
                PAGE_SIZE,
            )
            CheckResult(
                Outcome.UNKNOWN,
                reason = "Jira project $projectKey has more open issues than this check will read " +
                    "($MAX_PAGES pages of $PAGE_SIZE) — cannot confirm none are in scope",
                reasonKind = ReasonKind.SYSTEM_UNAVAILABLE,
            )
        } catch (e: Exception) {
            log.warn("Jira issue search failed for $projectKey: ${e.message}")
            CheckResult(Outcome.UNKNOWN, reason = "Jira issue search unavailable", reasonKind = ReasonKind.SYSTEM_UNAVAILABLE)
        }
    }
}
