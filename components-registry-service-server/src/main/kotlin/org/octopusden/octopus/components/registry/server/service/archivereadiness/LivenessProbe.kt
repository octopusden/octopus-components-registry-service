package org.octopusden.octopus.components.registry.server.service.archivereadiness

import com.atlassian.jira.rest.client.api.JiraRestClient
import org.octopusden.octopus.components.registry.server.config.ArchiveReadinessProperties
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Liveness of each external connection archive-readiness depends on, established once per
 * [ArchiveReadinessAssembler.assemble] call (design.md decision 12: "each system is proved live
 * once"). There are four independent connections, not three — the issue tracker is reached
 * through two separate clients (Atlassian for issue search, octopus for project reads) that
 * share one on/off switch today but can fail independently (decision 12/17), so they are probed
 * as two separate fields even though [ArchiveReadinessProperties.isJiraConfigured] is currently
 * their only switch.
 *
 * **Not every connection has a real system-wide probe call available:**
 * - TeamCity: `TeamcityClient.getServer()` (`GET .../server`) is a genuine lightweight,
 *   project-independent "is the server reachable and my credential valid" call — verified
 *   against `teamcity-client:2.0.98` sources.
 * - Jira issue-search (Atlassian): `JiraRestClient.sessionClient.getCurrentSession()` is the
 *   equivalent "who am I" call on `jira-rest-java-client-api:5.2.7` — lightweight, no project
 *   key needed.
 * - VCS (vcs-facade): `VcsFacadeClient` (`vcsfacade:client:3.0.36`) exposes no health/info/
 *   version endpoint — every method needs an `sshUrl`, `issueKeys`, or similar per-target
 *   argument. There is also no configured/unconfigured concept for VCS anywhere in this
 *   codebase (unlike the nullable Jira client beans, `VcsFacadeClient` is a plain non-nullable
 *   dependency of [RepositoryChecker]), so VCS is always treated as configured. With no probe
 *   call available, VCS liveness is always reported live; actual outage detection is deferred
 *   to [RepositoryChecker]'s own existing fail-closed-to-UNKNOWN behaviour, per target.
 * - Jira project-read (octopus): the octopus `JiraClient` interface's only read is
 *   `getProject(projectKey)`, which necessarily needs a project key — there is no
 *   project-independent call. Same fallback as VCS: reported live whenever configured, with
 *   outage detection deferred to [JiraProjectChecker]'s existing per-target fail-closed
 *   behaviour.
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
    private val jiraRestClient: JiraRestClient?,
    private val archiveReadinessProperties: ArchiveReadinessProperties,
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
        val teamcityConfigured = teamcityProperties.baseUrl.isNotBlank()
        val jiraIssuesConfigured = archiveReadinessProperties.isJiraConfigured()
        // Jira project-read shares today's single on/off switch with issue-search (decision 17
        // notes this may diverge in future), but is kept as its own field/call site so a future
        // split switch is a small change, not a rewrite.
        val jiraProjectConfigured = archiveReadinessProperties.isJiraConfigured()

        return LivenessSnapshot(
            // No configured/unconfigured concept exists for VCS in this codebase, and no
            // system-wide probe call is available on VcsFacadeClient — see class kdoc.
            vcsConfigured = true,
            vcsLive = true,
            teamcityConfigured = teamcityConfigured,
            teamcityLive = teamcityConfigured && probeTeamcity(),
            jiraIssuesConfigured = jiraIssuesConfigured,
            jiraIssuesLive = jiraIssuesConfigured && probeJiraIssues(),
            jiraProjectConfigured = jiraProjectConfigured,
            // No project-independent probe call is available on the octopus JiraClient
            // interface — see class kdoc. Reported live whenever configured; outage detection
            // is deferred to JiraProjectChecker's existing per-target fail-closed behaviour.
            jiraProjectLive = jiraProjectConfigured,
        )
    }

    // Catching Exception broadly is deliberate: this probe must fail closed to `live = false`
    // on ANY failure from TeamCity, not just specific exception types — an unanticipated
    // exception type from a third-party client is itself evidence the system couldn't be
    // consulted reliably. Same rationale as TcDescendantLookup.findDescendantsAndSelf.
    @Suppress("TooGenericExceptionCaught")
    private fun probeTeamcity(): Boolean =
        try {
            teamcityClient().getServer()
            true
        } catch (e: Exception) {
            log.warn("TeamCity liveness probe failed: ${e.message}")
            false
        }

    // TooGenericExceptionCaught: same fail-closed rationale as probeTeamcity above.
    @Suppress("TooGenericExceptionCaught")
    private fun probeJiraIssues(): Boolean {
        val client = jiraRestClient ?: return false
        return try {
            client.sessionClient.getCurrentSession().claim()
            true
        } catch (e: Exception) {
            log.warn("Jira issue-search liveness probe failed: ${e.message}")
            false
        }
    }
}
