package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.config.ArchiveReadinessProperties
import org.octopusden.octopus.components.registry.server.jira.JiraIssueSearchClient
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.common.exception.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Probes liveness of each external connection once per [ArchiveReadinessAssembler.assemble] call.
 * The issue tracker counts as two independent connections ([JiraIssueSearchClient] for issue
 * search, the octopus `JiraClient` for project reads), since they can fail independently even
 * though they share one configured/unconfigured switch today.
 *
 * TeamCity and Jira issue-search each have a real lightweight "is this connection alive" call.
 * VCS has no such call, so it probes via `getRepository` against a sentinel URL that can never be
 * real: both a [NotFoundException] and "no configured VCS service for this URL" count as a live
 * answer. Jira project-read has no probe call at all and is reported live whenever configured,
 * deferring outage detection to [JiraProjectChecker]'s own per-target checks.
 */
data class LivenessSnapshot(
    val vcsConfigured: Boolean,
    val vcsLive: Boolean,
    val teamcityConfigured: Boolean,
    val teamcityLive: Boolean,
    val jiraIssuesConfigured: Boolean,
    val jiraIssuesLive: Boolean,
    val jiraProjectConfigured: Boolean,
    val jiraProjectLive: Boolean,
)

@Service
class LivenessProbe(
    private val teamcityProperties: TeamcityProperties,
    private val jiraSearchClient: JiraIssueSearchClient?,
    private val archiveReadinessProperties: ArchiveReadinessProperties,
    private val vcsFacadeClient: VcsFacadeClient?,
    // Allows tests to inject a mock without needing to create a real TCP connection.
    // Production callers leave this null; Spring injects only the beans/properties above.
    private val teamcityClientOverride: TeamcityClient? = null,
) {
    private val log = LoggerFactory.getLogger(LivenessProbe::class.java)

    // Lazily initialised so a blank baseUrl does not attempt a connection at startup — same
    // pattern as TcDescendantLookup.lazyClient.
    private val lazyTeamcityClient: TeamcityClient by lazy {
        TeamcityClassicClient(
            object : ClientParametersProvider {
                override fun getApiUrl(): String = teamcityProperties.baseUrl.trimEnd('/')

                override fun getAuth(): CredentialProvider =
                    StandardBasicCredCredentialProvider(teamcityProperties.username, teamcityProperties.password)
            },
        )
    }

    private fun teamcityClient(): TeamcityClient = teamcityClientOverride ?: lazyTeamcityClient

    /** Probes every configured connection exactly once. Never throws — a failed probe reports `live = false`. */
    fun probe(): LivenessSnapshot {
        val vcsConfigured = archiveReadinessProperties.vcsFacade.baseUrl.isNotBlank()
        val teamcityConfigured = teamcityProperties.baseUrl.isNotBlank()
        val jiraIssuesConfigured = archiveReadinessProperties.isJiraConfigured()
        val jiraProjectConfigured = archiveReadinessProperties.isJiraConfigured()

        if (!vcsConfigured) log.info("VCS check disabled: archive-readiness.vcs-facade.base-url is blank")
        if (!teamcityConfigured) log.info("TeamCity check disabled: teamcity.base-url is blank")
        if (!jiraIssuesConfigured) log.info("Jira issue-search check disabled: archive-readiness.jira.base-url is blank")

        val snapshot =
            LivenessSnapshot(
                vcsConfigured = vcsConfigured,
                vcsLive = vcsConfigured && probeVcs(),
                teamcityConfigured = teamcityConfigured,
                teamcityLive = teamcityConfigured && probeTeamcity(),
                jiraIssuesConfigured = jiraIssuesConfigured,
                jiraIssuesLive = jiraIssuesConfigured && probeJiraIssues(),
                jiraProjectConfigured = jiraProjectConfigured,
                jiraProjectLive = jiraProjectConfigured,
            )
        log.info(
            "Archive-readiness liveness snapshot: vcs(configured={}, live={}) teamcity(configured={}, live={}) " +
                "jiraIssues(configured={}, live={}) jiraProject(configured={}, live={})",
            snapshot.vcsConfigured,
            snapshot.vcsLive,
            snapshot.teamcityConfigured,
            snapshot.teamcityLive,
            snapshot.jiraIssuesConfigured,
            snapshot.jiraIssuesLive,
            snapshot.jiraProjectConfigured,
            snapshot.jiraProjectLive,
        )
        return snapshot
    }

    @Suppress("TooGenericExceptionCaught")
    private fun probeTeamcity(): Boolean =
        try {
            teamcityClient().getServer()
            true
        } catch (e: Exception) {
            log.warn("TeamCity liveness probe failed: ${e.message}")
            false
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun probeVcs(): Boolean {
        val client = vcsFacadeClient ?: return false
        return try {
            client.getRepository(VCS_LIVENESS_SENTINEL_URL)
            true
        } catch (e: NotFoundException) {
            true
        } catch (e: Exception) {
            if (e.message?.contains("There is no configured VCS service for") == true) {
                true
            } else {
                log.warn("VCS liveness probe failed: ${e.message}")
                false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun probeJiraIssues(): Boolean {
        val client = jiraSearchClient ?: return false
        return try {
            client.checkSession()
            true
        } catch (e: Exception) {
            log.warn("Jira issue-search liveness probe failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val VCS_LIVENESS_SENTINEL_URL = "ssh://git@archive-readiness-liveness-probe.invalid/does-not-exist.git"
    }
}
