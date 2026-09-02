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
            CheckResult(Outcome.UNKNOWN, reason = "Jira issue search unavailable", reasonKind = ReasonKind.SYSTEM_UNAVAILABLE)
        }
    }
}
