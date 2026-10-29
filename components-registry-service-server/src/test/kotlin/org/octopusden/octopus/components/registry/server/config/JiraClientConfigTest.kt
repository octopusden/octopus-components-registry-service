package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JiraClientConfigTest {
    private val config = JiraClientConfig()

    @Test
    fun `blank base url yields no clients`() {
        val props = ArchiveReadinessProperties()
        assertNull(config.jiraIssueSearchClient(props))
        assertNull(config.octopusJiraClient(props))
    }

    @Test
    fun `configured base url builds real clients`() {
        val props =
            ArchiveReadinessProperties(
                jira =
                    ArchiveReadinessProperties.JiraConnectionProperties(
                        baseUrl = "http://jira.example",
                        username = "user",
                        password = "pass",
                    ),
            )
        assertNotNull(config.jiraIssueSearchClient(props))
        assertNotNull(config.octopusJiraClient(props))
    }
}
