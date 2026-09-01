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
                )
            )
        )
        val json = mapper.writeValueAsString(response)
        assertThat(json).contains("\"ready\":false")
        assertThat(json).contains("\"targetKind\":\"REPOSITORY\"")
        assertThat(json).contains("\"outcome\":\"FAILED\"")
        assertThat(json).contains("\"openIssues\":[]")
    }
}
