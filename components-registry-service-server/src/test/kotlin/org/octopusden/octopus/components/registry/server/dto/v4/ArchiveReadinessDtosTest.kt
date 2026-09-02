package org.octopusden.octopus.components.registry.server.dto.v4

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArchiveReadinessDtosTest {
    private val mapper = ObjectMapper()

    @Test
    fun `serialises to contract shape`() {
        val response = ArchiveReadinessResponse(
            ready = false,
            entries = listOf(
                ArchiveReadinessEntry(
                    targetKind = TargetKind.REPOSITORY,
                    targetId = "ssh://git.example.com/repo.git",
                    targetUrl = "https://git.example.com/repo",
                    outcome = Outcome.FAILED,
                    reason = null,
                    sharedWith = emptyList(),
                    openIssues = emptyList(),
                ),
            ),
        )
        val json = mapper.writeValueAsString(response)
        assertThat(json).contains("\"ready\":false")
        assertThat(json).contains("\"targetKind\":\"REPOSITORY\"")
        assertThat(json).contains("\"outcome\":\"FAILED\"")
        assertThat(json).contains("\"openIssues\":[]")
        // FAILED carries no remedy classification.
        assertThat(json).contains("\"reasonKind\":null")
    }

    @Test
    fun `serialises a populated reasonKind on an UNKNOWN entry`() {
        val response = ArchiveReadinessResponse(
            ready = false,
            entries = listOf(
                ArchiveReadinessEntry(
                    targetKind = TargetKind.JIRA_PROJECT,
                    targetId = "PROJ",
                    targetUrl = null,
                    outcome = Outcome.UNKNOWN,
                    reason = "No retired Jira project categories configured",
                    reasonKind = ReasonKind.NOT_CONFIGURED,
                    sharedWith = emptyList(),
                    openIssues = emptyList(),
                ),
            ),
        )
        val json = mapper.writeValueAsString(response)
        assertThat(json).contains("\"outcome\":\"UNKNOWN\"")
        assertThat(json).contains("\"reasonKind\":\"NOT_CONFIGURED\"")
    }
}
