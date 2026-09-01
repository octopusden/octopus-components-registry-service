package org.octopusden.octopus.components.registry.server.service.archivereadiness

import com.atlassian.jira.rest.client.api.JiraRestClient
import org.octopusden.octopus.components.registry.server.dto.v4.JiraIssueRef
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

private val BARE_VERSION = Regex("^\\d")

@Service
class JiraIssuesChecker(
    private val jiraRestClient: JiraRestClient?,
    private val pairResolver: JiraEffectivePairResolver,
) {
    private val log = LoggerFactory.getLogger(JiraIssuesChecker::class.java)

    // componentId is accepted (not used in the body) to keep this checker's call shape
    // symmetric with TargetChecker.check(CheckTarget) — sharedWith is always empty here
    // (sharing never excuses open issues), so no component-scoped sharing lookup is needed.
    @Suppress("UnusedParameter")
    fun checkPair(
        projectKey: String,
        prefix: String?,
        componentId: UUID,
    ): CheckResult {
        if (jiraRestClient == null) return CheckResult(Outcome.UNKNOWN, reason = "Jira issue search not configured")
        if (prefix == null && pairResolver.hasNullPrefixConflict(projectKey)) {
            return CheckResult(
                Outcome.UNKNOWN,
                reason = "More than one component claims the default (no-prefix) bucket on Jira " +
                    "project $projectKey — registry data to correct",
            )
        }
        val jql = if (prefix != null) {
            "project = \"$projectKey\" AND fixVersion ~ \"$prefix*\" AND statusCategory != Done"
        } else {
            "project = \"$projectKey\" AND statusCategory != Done"
        }
        return try {
            val results = jiraRestClient.searchClient
                .searchJql(jql, 50, 0, setOf("summary", "fixVersions", "status"))
                .claim()
            val matching = results.issues.filter { issue ->
                prefix != null || (issue.fixVersions?.any { v -> BARE_VERSION.containsMatchIn(v.name ?: "") } == true)
                // ^ when prefix != null, the JQL clause already scoped it; when prefix == null,
                //   apply the bare-pattern test client-side on each issue's own recorded fix
                //   version values, never by excluding other pairs' registered prefixes.
            }
            val openIssues = matching.map { JiraIssueRef(it.key, it.summary ?: "") }
            if (openIssues.isEmpty()) {
                CheckResult(Outcome.PASSED)
            } else {
                CheckResult(Outcome.FAILED, openIssues = openIssues)
            }
        } catch (e: Exception) {
            log.warn("Jira issue search failed for $projectKey: ${e.message}")
            CheckResult(Outcome.UNKNOWN, reason = "Jira issue search unavailable")
        }
    }
}
