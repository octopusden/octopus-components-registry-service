package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProjects
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator

class TcDescendantLookupTest {

    private val client = mock(TeamcityClient::class.java)
    private val properties = TeamcityProperties(baseUrl = "http://tc.example.com")
    private val lookup = TcDescendantLookup(properties, clientOverride = client)

    /**
     * Workaround for Mockito's `any(Class)` returning null in Kotlin — the null-check
     * throws NPE before Mockito can intercept. We fall back to an empty [ProjectLocator]
     * as the non-null sentinel so stubbing still matches any locator argument.
     */
    private fun anyLocator(): ProjectLocator =
        org.mockito.ArgumentMatchers.any(ProjectLocator::class.java) ?: ProjectLocator()

    @Test
    fun `includes the queried project itself in the result`() {
        doReturn(TeamcityProjects(projects = emptyList()))
            .`when`(client).getProjectsWithLocatorAndFields(anyLocator(), anyString())
        val result = lookup.findDescendantsAndSelf("TC_ROOT") as TcDescendantResult.Found
        assertThat(result.projectIds).contains("TC_ROOT")
    }

    @Test
    fun `includes direct descendants returned by affectedProject`() {
        val child = TeamcityProject(id = "TC_CHILD", name = "child", archived = false,
            webUrl = "", href = "")
        doReturn(TeamcityProjects(projects = listOf(child)))
            .`when`(client).getProjectsWithLocatorAndFields(anyLocator(), anyString())
        val result = lookup.findDescendantsAndSelf("TC_ROOT") as TcDescendantResult.Found
        assertThat(result.projectIds).containsExactlyInAnyOrder("TC_ROOT", "TC_CHILD")
    }

    @Test
    fun `archived flag requested and populated correctly`() {
        val archivedChild = TeamcityProject(id = "TC_ARCHIVED", name = "archived", archived = true,
            webUrl = "", href = "")
        val liveChild = TeamcityProject(id = "TC_LIVE", name = "live", archived = false,
            webUrl = "", href = "")
        doReturn(TeamcityProjects(projects = listOf(archivedChild, liveChild)))
            .`when`(client).getProjectsWithLocatorAndFields(anyLocator(), anyString())
        val result = lookup.findDescendantsAndSelf("TC_ROOT") as TcDescendantResult.Found
        assertThat(result.archivedIds).contains("TC_ARCHIVED")
        assertThat(result.archivedIds).doesNotContain("TC_LIVE")
    }

    @Test
    fun `TC failure returns SystemUnavailable not empty set`() {
        doThrow(RuntimeException("connection refused"))
            .`when`(client).getProjectsWithLocatorAndFields(anyLocator(), anyString())
        val result = lookup.findDescendantsAndSelf("TC_ROOT")
        assertThat(result).isInstanceOf(TcDescendantResult.SystemUnavailable::class.java)
    }
}
