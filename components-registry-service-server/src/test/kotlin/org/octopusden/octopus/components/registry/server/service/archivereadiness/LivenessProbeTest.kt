package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.config.ArchiveReadinessProperties
import org.octopusden.octopus.components.registry.server.jira.JiraIssueSearchClient
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityServer
import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.common.exception.NotFoundException

class LivenessProbeTest {
    private val teamcityClient = mock<TeamcityClient>()
    private val jiraSearchClient = mock<JiraIssueSearchClient>()
    private val vcsFacadeClient = mock<VcsFacadeClient>()

    private fun probe(
        teamcityBaseUrl: String = "http://tc.example.com",
        jiraBaseUrl: String = "http://jira.example.com",
        jiraClient: JiraIssueSearchClient? = jiraSearchClient,
        vcsBaseUrl: String = "http://vcs-facade.example.com",
        vcsClient: VcsFacadeClient? = vcsFacadeClient,
    ) = LivenessProbe(
        teamcityProperties = TeamcityProperties(baseUrl = teamcityBaseUrl),
        jiraSearchClient = jiraClient,
        archiveReadinessProperties = ArchiveReadinessProperties(
            jira = ArchiveReadinessProperties.JiraConnectionProperties(baseUrl = jiraBaseUrl),
            vcsFacade = ArchiveReadinessProperties.VcsFacadeConnectionProperties(baseUrl = vcsBaseUrl),
        ),
        vcsFacadeClient = vcsClient,
        teamcityClientOverride = teamcityClient,
    )

    private fun stubJiraSessionSucceeds() {
        doNothing().whenever(jiraSearchClient).checkSession()
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
        stubJiraSessionSucceeds()

        probe().probe()

        verify(jiraSearchClient, times(1)).checkSession()
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
        whenever(jiraSearchClient.checkSession()).thenThrow(RuntimeException("401 unauthorized"))

        val snapshot = probe().probe()

        assertThat(snapshot.jiraIssuesConfigured).isTrue()
        assertThat(snapshot.jiraIssuesLive).isFalse()
    }

    @Test
    fun `configured VCS connection is probed exactly once`() {
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        stubJiraSessionSucceeds()
        whenever(vcsFacadeClient.getRepository(any())).thenReturn(mock())
        val snapshot = probe().probe()
        assertThat(snapshot.vcsConfigured).isTrue()
        assertThat(snapshot.vcsLive).isTrue()
        verify(vcsFacadeClient, times(1)).getRepository(any())
    }

    @Test
    fun `unconfigured VCS is never probed and reports configured false`() {
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        stubJiraSessionSucceeds()
        val snapshot = probe(vcsBaseUrl = "").probe()
        assertThat(snapshot.vcsConfigured).isFalse()
        assertThat(snapshot.vcsLive).isFalse()
        verify(vcsFacadeClient, never()).getRepository(any())
    }

    @Test
    fun `configured VCS whose probe call throws reports live false, not an exception out of probe`() {
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        stubJiraSessionSucceeds()
        whenever(vcsFacadeClient.getRepository(any())).thenThrow(RuntimeException("connection refused"))
        val snapshot = probe().probe()
        assertThat(snapshot.vcsConfigured).isTrue()
        assertThat(snapshot.vcsLive).isFalse()
    }

    @Test
    fun `VCS sentinel URL not found on a real provider is still live`() {
        // The sentinel host is never a real VCS provider, so a NotFoundException here means the
        // server understood the request and answered — proof of life, not a real absence.
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        stubJiraSessionSucceeds()
        whenever(vcsFacadeClient.getRepository(any())).thenThrow(NotFoundException("not found"))
        val snapshot = probe().probe()
        assertThat(snapshot.vcsConfigured).isTrue()
        assertThat(snapshot.vcsLive).isTrue()
    }

    @Test
    fun `VCS sentinel URL matching no configured provider is still live`() {
        // Same rationale as the NotFoundException case above, for the other well-formed
        // "I answered, this URL just isn't mine" response RepositoryChecker already recognizes.
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        stubJiraSessionSucceeds()
        whenever(vcsFacadeClient.getRepository(any()))
            .thenThrow(RuntimeException("There is no configured VCS service for this url"))
        val snapshot = probe().probe()
        assertThat(snapshot.vcsConfigured).isTrue()
        assertThat(snapshot.vcsLive).isTrue()
    }

    @Test
    fun `VCS bean absent (no bean produced for a blank base url) reports live false without throwing`() {
        // VcsFacadeClientConfig produces no bean at all when the base url is blank, so
        // LivenessProbe receives a null VcsFacadeClient — this must not NPE.
        whenever(teamcityClient.getServer()).thenReturn(TeamcityServer("2024.03"))
        stubJiraSessionSucceeds()
        val snapshot = probe(vcsClient = null).probe()
        assertThat(snapshot.vcsLive).isFalse()
        verify(vcsFacadeClient, never()).getRepository(any())
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
