package org.octopusden.octopus.components.registry.server.service.archivereadiness

import com.atlassian.jira.rest.client.api.JiraRestClient
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.JiraIssueRef
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

private val BARE_VERSION = Regex("^\\d")
private const val PAGE_SIZE = 50

// Depends transitively on JPA repos via JiraEffectivePairResolver — drop in no-db mode too.
@ConditionalOnDatabaseEnabled
@Service
class JiraIssuesChecker(
    private val jiraRestClient: JiraRestClient?,
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
        if (jiraRestClient == null) {
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
        return try {
            // A null `fields` set (confirmed via bytecode: searchJqlImplGet skips the "fields"
            // query param entirely when this is null, exactly like the client's own
            // searchJql(String)-only overload does) leaves Jira's normal default field set in
            // place, rather than an explicit allowlist this check would otherwise have to keep in
            // lockstep with jira-rest-java-client-core's own unconditional parsing requirements —
            // omitting even one of those (as "issuetype", then "created" here did in production)
            // throws a raw JSONException for every issue on the page, not just the field itself.
            val results = jiraRestClient.searchClient
                .searchJql(jql, PAGE_SIZE, 0, null)
                .claim()
            val page = results.issues.toList()
            val matching = page.filter { issue ->
                val fixVersionNames = issue.fixVersions?.mapNotNull { it.name } ?: emptyList()
                when {
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
            val openIssues = matching.map { JiraIssueRef(it.key, it.summary ?: "") }
            when {
                openIssues.isNotEmpty() -> CheckResult(Outcome.FAILED, openIssues = openIssues)
                // A match found among the fetched page is trustworthy evidence on its own (more
                // open issues on later pages would only reinforce FAILED), but an EMPTY match on a
                // truncated page is not trustworthy evidence of PASSED — the one matching issue
                // could be sitting unread on page 2. Filtering is entirely client-side now (see the
                // JQL comment above), so a truncated fetch would otherwise silently report a
                // component ready to archive while its own open issues go unseen — the fail-open
                // direction this check exists to prevent everywhere else.
                results.total > page.size -> {
                    log.warn(
                        "JIRA_ISSUES: {} has {} open issues, more than the {} this check reads — cannot confirm none are in scope",
                        projectKey,
                        results.total,
                        PAGE_SIZE,
                    )
                    CheckResult(
                        Outcome.UNKNOWN,
                        reason = "Jira project $projectKey has ${results.total} open issues, more than the " +
                            "$PAGE_SIZE this check reads — cannot confirm none are in scope",
                        reasonKind = ReasonKind.SYSTEM_UNAVAILABLE,
                    )
                }
                else -> CheckResult(Outcome.PASSED)
            }
        } catch (e: Exception) {
            log.warn("Jira issue search failed for $projectKey: ${e.message}")
            CheckResult(Outcome.UNKNOWN, reason = "Jira issue search unavailable", reasonKind = ReasonKind.SYSTEM_UNAVAILABLE)
        }
    }
}
