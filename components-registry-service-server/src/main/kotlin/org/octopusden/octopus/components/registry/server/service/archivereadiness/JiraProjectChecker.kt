package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.config.ArchiveReadinessProperties
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.infrastructure.jira.JiraClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class JiraProjectChecker(
    private val octopusJiraClient: JiraClient?,
    private val sharingHelper: SharingHelper,
    private val properties: ArchiveReadinessProperties,
) {
    private val log = LoggerFactory.getLogger(JiraProjectChecker::class.java)

    // Catching Exception broadly is deliberate: this check must fail closed to UNKNOWN on
    // ANY failure from the Jira project read call, not just specific exception types — an
    // unanticipated exception type from a third-party client is itself evidence the system
    // couldn't be consulted reliably.
    @Suppress("TooGenericExceptionCaught")
    fun checkProject(
        projectKey: String,
        componentId: UUID,
    ): CheckResult {
        if (octopusJiraClient == null) return CheckResult(Outcome.UNKNOWN, reason = "Jira client not configured")
        if (properties.retiredJiraProjectCategories.isEmpty()) {
            return CheckResult(Outcome.UNKNOWN, reason = "No retired Jira project categories configured")
        }
        return try {
            val project = octopusJiraClient.getProject(projectKey)
            // `Project.projectCategory` is a nullable `ProjectCategory(name: String)` DTO, not a
            // plain String — compare its `.name`, and treat an absent category as not-retired
            // (never as a match) rather than throwing on the null.
            val category = project.projectCategory?.name
            val retired = category != null && category in properties.retiredJiraProjectCategories
            val shared = sharingHelper.sharedWithForJiraProject(projectKey, componentId)
            when {
                shared.isNotEmpty() -> CheckResult(Outcome.PASSED, sharedWith = shared)
                retired -> CheckResult(Outcome.PASSED)
                else -> CheckResult(Outcome.FAILED)
            }
        } catch (e: Exception) {
            log.warn("Jira project read failed for $projectKey: ${e.message}")
            CheckResult(Outcome.UNKNOWN, reason = "Jira project client unavailable")
        }
    }
}
