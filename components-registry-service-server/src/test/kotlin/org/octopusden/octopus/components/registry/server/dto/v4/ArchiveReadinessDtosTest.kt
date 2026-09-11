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
                    outcome = Outcome.NOT_COMPLETED,
                    reason = null,
                    sharedWith = emptyList(),
                    openIssues = emptyList(),
                ),
            ),
        )
        val json = mapper.writeValueAsString(response)
        assertThat(json).contains("\"ready\":false")
        assertThat(json).contains("\"targetKind\":\"REPOSITORY\"")
        assertThat(json).contains("\"outcome\":\"NOT_COMPLETED\"")
        assertThat(json).contains("\"openIssues\":[]")
        // NOT_COMPLETED carries no remedy classification.
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
