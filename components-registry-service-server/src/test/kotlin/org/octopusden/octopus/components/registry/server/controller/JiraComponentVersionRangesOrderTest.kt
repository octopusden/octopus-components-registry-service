package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.releng.dto.JiraComponent
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * CTL-001 / CTL-002: Guards against controller-level re-sorting of the
 * jira-component-version-ranges response.
 *
 * Regression context: a sorted List was introduced (and later reverted) in
 * ProjectControllerV2 and CommonControllerV2 to address compat ordering diffs.
 * The sort changed the raw HTTP JSON array order vs the V1 baseline, producing
 * 46 STRUCTURAL_DIFFs in the compat-1.8 (git-mode) run — a region that had
 * been clean. The correct invariant is: the controller must preserve the order
 * returned by the resolver and must NOT apply additional sorting.
 *
 * Pure unit tests with no Spring context: standalone MockMvc + mocked resolver.
 */
class JiraComponentVersionRangesOrderTest {

    private val resolver: ComponentRegistryResolver = mock(ComponentRegistryResolver::class.java)
    private val projectController = ProjectControllerV2(resolver)
    private val mockMvc = MockMvcBuilders.standaloneSetup(projectController).build()

    @Test
    @DisplayName("CTL-001: ProjectControllerV2 preserves resolver element order without re-sorting")
    fun `CTL-001 project endpoint preserves resolver order`() {
        // Resolver returns Z-Comp BEFORE A-Comp (reverse-alphabetical).
        // A controller sort by componentName would flip this → $[0] == A-Comp → test RED.
        doReturn(linkedSetOf(makeRange("Z-Comp", "(,1.0)"), makeRange("A-Comp", "[1.0,)")))
            .`when`(resolver).getJiraComponentVersionRangesByProject("TPROJ")

        mockMvc
            .perform(
                get("/rest/api/2/projects/TPROJ/jira-component-version-ranges")
                    .accept(MediaType.APPLICATION_JSON),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].componentName").value("Z-Comp"))
            .andExpect(jsonPath("$[1].componentName").value("A-Comp"))
    }

    /**
     * Builds a minimal [JiraComponentVersionRange] mock that can pass through
     * [org.octopusden.octopus.components.registry.server.mapper.Mappers.toDTO]
     * without NPE.
     *
     * Deep stubs cover the nested JiraComponent property chain. The four
     * non-nullable String fields of [ComponentVersionFormatDTO] are stubbed
     * to return "" so Kotlin's generated null-check does not throw.
     * VCSSettings is stubbed explicitly so that [VCSSettings.versionControlSystemRoots]
     * returns an empty list rather than a raw mock (which would fail on iteration).
     */
    private fun makeRange(name: String, range: String): JiraComponentVersionRange {
        val jiraComponent = mock(JiraComponent::class.java, RETURNS_DEEP_STUBS)
        // Non-null String params in JiraComponentDTO / ComponentVersionFormatDTO constructors;
        // Kotlin inserts null-checks for these, so explicit stubs are required.
        doReturn("").`when`(jiraComponent).projectKey
        `when`(jiraComponent.componentVersionFormat.majorVersionFormat).thenReturn("")
        `when`(jiraComponent.componentVersionFormat.releaseVersionFormat).thenReturn("")
        `when`(jiraComponent.componentVersionFormat.buildVersionFormat).thenReturn("")
        `when`(jiraComponent.componentVersionFormat.lineVersionFormat).thenReturn("")

        val vcsSettings = mock(VCSSettings::class.java)
        doReturn(emptyList<Any>()).`when`(vcsSettings).versionControlSystemRoots

        return mock(JiraComponentVersionRange::class.java).also { r ->
            doReturn(name).`when`(r).componentName
            doReturn(range).`when`(r).versionRange
            doReturn(jiraComponent).`when`(r).component
            doReturn(vcsSettings).`when`(r).vcsSettings
            doReturn(null).`when`(r).distribution
        }
    }
}
