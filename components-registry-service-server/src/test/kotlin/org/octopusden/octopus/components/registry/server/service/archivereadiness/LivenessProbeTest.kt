package org.octopusden.octopus.components.registry.server.service.archivereadiness

import com.atlassian.jira.rest.client.api.JiraRestClient
import com.atlassian.jira.rest.client.api.SessionRestClient
import com.atlassian.jira.rest.client.api.domain.Session
import io.atlassian.util.concurrent.Promise
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.config.ArchiveReadinessProperties
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityServer

class LivenessProbeTest {
    private val teamcityClient = mock<TeamcityClient>()
    private val jiraRestClient = mock<JiraRestClient>()

    private fun probe(
        teamcityBaseUrl: String = "http://tc.example.com",
        jiraBaseUrl: String = "http://jira.example.com",
        jiraClient: JiraRestClient? = jiraRestClient,
    ) = LivenessProbe(
        teamcityProperties = TeamcityProperties(baseUrl = teamcityBaseUrl),
        jiraRestClient = jiraClient,
        archiveReadinessProperties = ArchiveReadinessProperties(
            jira = ArchiveReadinessProperties.JiraConnectionProperties(baseUrl = jiraBaseUrl),
        ),
        teamcityClientOverride = teamcityClient,
    )

    private fun stubJiraSessionSucceeds() {
        val sessionClient = mock<SessionRestClient>()
        val promise = mock<Promise<Session>>()
        whenever(jiraRestClient.sessionClient).thenReturn(sessionClient)
        whenever(sessionClient.getCurrentSession()).thenReturn(promise)
        whenever(promise.claim()).thenReturn(mock<Session>())
    }

    @Test
    fun `configured TeamCity connection is probed exactly once`() {
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        stubJiraSessionSucceeds()
        probe().probe()
        verify(teamcityClient, times(1)).getServer()
    }

    @Test
    fun `configured Jira issue-search connection is probed exactly once`() {
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        val sessionClient = mock<SessionRestClient>()
        val promise = mock<Promise<Session>>()
        whenever(jiraRestClient.sessionClient).thenReturn(sessionClient)
        whenever(sessionClient.getCurrentSession()).thenReturn(promise)
        whenever(promise.claim()).thenReturn(mock<Session>())

        probe().probe()

        verify(sessionClient, times(1)).getCurrentSession()
    }

    @Test
    fun `unconfigured TeamCity is never called and reports configured false`() {
        stubJiraSessionSucceeds()
        val snapshot = probe(teamcityBaseUrl = "").probe()
        assertThat(snapshot.teamcityConfigured).isFalse()
        assertThat(snapshot.teamcityLive).isFalse()
        verify(teamcityClient, never()).getServer()
    }

    @Test
    fun `unconfigured Jira is never called and reports configured false`() {
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        val snapshot = probe(jiraBaseUrl = "", jiraClient = null).probe()
        assertThat(snapshot.jiraIssuesConfigured).isFalse()
        assertThat(snapshot.jiraIssuesLive).isFalse()
        assertThat(snapshot.jiraProjectConfigured).isFalse()
        assertThat(snapshot.jiraProjectLive).isFalse()
    }

    @Test
    fun `configured TeamCity whose call throws reports live false, not an exception out of probe`() {
        whenever(teamcityClient.getServer()).thenThrow(RuntimeException("connection refused"))
        stubJiraSessionSucceeds()
        val snapshot = probe().probe()
        assertThat(snapshot.teamcityConfigured).isTrue()
        assertThat(snapshot.teamcityLive).isFalse()
    }

    @Test
    fun `configured Jira issue-search whose call throws reports live false, not an exception out of probe`() {
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        val sessionClient = mock<SessionRestClient>()
        whenever(jiraRestClient.sessionClient).thenReturn(sessionClient)
        whenever(sessionClient.getCurrentSession()).thenThrow(RuntimeException("401 unauthorized"))

        val snapshot = probe().probe()

        assertThat(snapshot.jiraIssuesConfigured).isTrue()
        assertThat(snapshot.jiraIssuesLive).isFalse()
    }

    @Test
    fun `VCS has no configured concept and no probe call - always reported live`() {
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        stubJiraSessionSucceeds()
        val snapshot = probe().probe()
        assertThat(snapshot.vcsConfigured).isTrue()
        assertThat(snapshot.vcsLive).isTrue()
    }

    @Test
    fun `Jira project-read has no per-project-independent probe call - live follows configured`() {
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        stubJiraSessionSucceeds()
        val snapshot = probe().probe()
        assertThat(snapshot.jiraProjectConfigured).isTrue()
        assertThat(snapshot.jiraProjectLive).isTrue()
    }
}
