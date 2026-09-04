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
 * Liveness of each external connection archive-readiness depends on, established once per
 * [ArchiveReadinessAssembler.assemble] call (design.md decision 12: "each system is proved live
 * once"). There are four independent connections, not three — the issue tracker is reached
 * through two separate clients ([JiraIssueSearchClient] for issue search, the octopus `JiraClient`
 * for project reads) that share one on/off switch today but can fail independently (decision
 * 12/17), so they are probed as two separate fields even though
 * [ArchiveReadinessProperties.isJiraConfigured] is currently their only switch.
 *
 * **Not every connection has a real system-wide probe call available:**
 * - TeamCity: `TeamcityClient.getServer()` (`GET .../server`) is a genuine lightweight,
 *   project-independent "is the server reachable and my credential valid" call — verified
 *   against `teamcity-client:2.0.98` sources.
 * - Jira issue-search: `GET /rest/auth/1/session` ([JiraIssueSearchClient.checkSession]) is the
 *   equivalent "who am I" call against Jira's own REST API — lightweight, no project key needed.
 * - VCS (vcs-facade): no project-independent probe call exists on the real, deployed server —
 *   `indexReport`/`reindexRepository` (the "indexer" module) was removed from the server side at
 *   some point after `vcsfacade:client:3.0.36`'s API was written; the client interface still
 *   declares it (confirmed against `vcsfacade:client:3.0.37`'s own sources), so calling it fails
 *   every time with a 500 the client can't distinguish from a real outage. `getRepository(sshUrl)`
 *   against a sentinel URL that can never be a real repository is used instead: both a
 *   [NotFoundException] and a normal response prove the connection and credential are good, and
 *   only a genuine connection/5xx failure counts as not-live — confirmed live directly against
 *   the deployed server via `oc exec` (2026-09-03), bypassing the api-gateway, since the pinned
 *   client version can't be trusted against server API drift going forward. `vcsConfigured`
 *   follows `archive-readiness.vcs-facade.base-url` being non-blank, the same "blank =
 *   unconfigured, no entries" convention TeamCity/Jira already use (see
 *   [ArchiveReadinessProperties] and
 *   [org.octopusden.octopus.components.registry.server.config.VcsFacadeClientConfig], which
 *   produces no bean at all on a blank base URL).
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
        // Jira project-read shares today's single on/off switch with issue-search (decision 17
        // notes this may diverge in future), but is kept as its own field/call site so a future
        // split switch is a small change, not a rewrite.
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
                // No project-independent probe call is available on the octopus JiraClient
                // interface — see class kdoc. Reported live whenever configured; outage detection
                // is deferred to JiraProjectChecker's existing per-target fail-closed behaviour.
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

    // TooGenericExceptionCaught: same fail-closed rationale as probeTeamcity above. The sentinel
    // URL's host is deliberately not any real VCS provider — hardcoding one from this
    // environment's config would break the probe anywhere that host isn't configured — so
    // vcs-facade answers "no configured VCS service for" this URL rather than a genuine
    // NotFoundException. Both outcomes, like a normal response, are a well-formed answer from a
    // live server and are treated the same way here — RepositoryChecker's own message-matching
    // heuristic already establishes this exact string as recognized, not fragile guesswork.
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

    // TooGenericExceptionCaught: same fail-closed rationale as probeTeamcity above.
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
