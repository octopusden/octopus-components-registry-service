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

// SYS-047: depends (via JiraEffectivePairResolver) on a bean that injects JPA repositories, so
// it must be dropped in no-db mode too — see ConditionalOnDatabaseEnabled's kdoc ("or another
// bean so annotated").
@ConditionalOnDatabaseEnabled
@Service
class JiraIssuesChecker(
    private val jiraRestClient: JiraRestClient?,
    private val pairResolver: JiraEffectivePairResolver,
) {
    private val log = LoggerFactory.getLogger(JiraIssuesChecker::class.java)

    // componentId is accepted (not used in the body) to keep this checker's call shape
    // symmetric with TargetChecker.check(CheckTarget) — sharedWith is always empty here
    // (sharing never excuses open issues), so no component-scoped sharing lookup is needed.
    // TooGenericExceptionCaught: catching Exception broadly here is deliberate — this check
    // must fail closed to UNKNOWN on ANY failure from the Jira issue search call, not just
    // specific exception types, because an unanticipated exception type from a third-party
    // client is itself evidence the system couldn't be consulted reliably.
    @Suppress("UnusedParameter", "TooGenericExceptionCaught")
    fun checkPair(
        projectKey: String,
        prefix: String?,
        componentId: UUID,
    ): CheckResult {
        if (jiraRestClient == null) {
            return CheckResult(
                Outcome.UNKNOWN,
                reason = "Jira issue search not configured",
                reasonKind = ReasonKind.NOT_CONFIGURED,
            )
        }
        if (prefix == null && pairResolver.hasNullPrefixConflict(projectKey)) {
            return CheckResult(
                Outcome.UNKNOWN,
                reason = "More than one component claims the default (no-prefix) bucket on Jira " +
                    "project $projectKey — registry data to correct",
                reasonKind = ReasonKind.REGISTRY_DATA,
            )
        }
        // Whole-project scope applies only when this null-prefix pair is the SOLE claimant of
        // projectKey (spec.md "A sole claim on a project is scoped by the whole project") — the
        // null/null conflict case above is already handled, so if another pair exists here it
        // necessarily has its own non-null prefix, and the bare-version-pattern filter below
        // still applies. Only computed for the null-prefix path: a prefixed pair never needs it.
        val wholeProjectScope = prefix == null && !pairResolver.hasOtherPairOnProjectKey(projectKey)
        // `fixVersion` is a VERSION-typed JQL field: it does not support the `~` (CONTAINS)
        // operator, which is TEXT-field only (supported operators: = != > >= < <= is "is not" in
        // "not in"). A `fixVersion ~ "prefix*"` clause is therefore invalid JQL and would make
        // Jira reject the whole search. All prefix/bare-version matching is done client-side
        // below, on the `fixVersions` field already requested, instead.
        val jql = "project = \"$projectKey\" AND statusCategory != Done"
        return try {
            val results = jiraRestClient.searchClient
                .searchJql(jql, PAGE_SIZE, 0, setOf("summary", "fixVersions", "status"))
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
                results.total > page.size -> CheckResult(
                    Outcome.UNKNOWN,
                    reason = "Jira project $projectKey has ${results.total} open issues, more than the " +
                        "$PAGE_SIZE this check reads — cannot confirm none are in scope",
                    reasonKind = ReasonKind.SYSTEM_UNAVAILABLE,
                )
                else -> CheckResult(Outcome.PASSED)
            }
        } catch (e: Exception) {
            log.warn("Jira issue search failed for $projectKey: ${e.message}")
            CheckResult(Outcome.UNKNOWN, reason = "Jira issue search unavailable", reasonKind = ReasonKind.SYSTEM_UNAVAILABLE)
        }
    }
}
