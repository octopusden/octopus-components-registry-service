package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Regression test for a classpath gap that every earlier archive-readiness test missed: both
 * beans below return null without touching Atlassian's client classes when Jira is unconfigured,
 * so a suite that never sets a real jira.base-url never exercises the code path that actually
 * constructs [com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClient] — which
 * is exactly the path that threw NoClassDefFoundError on javax.ws.rs.core.UriBuilder once
 * jersey-client (its transitive source) was excluded to fix the unrelated Eureka/Jersey conflict.
 */
class JiraClientConfigTest {
    private val config = JiraClientConfig()

    @Test
    fun `blank base url yields no clients`() {
        val props = ArchiveReadinessProperties()
        assertNull(config.atlassianJiraRestClient(props))
        assertNull(config.octopusJiraClient(props))
    }

    @Test
    fun `configured base url builds a real AsynchronousJiraRestClient`() {
        val props =
            ArchiveReadinessProperties(
                jira =
                    ArchiveReadinessProperties.JiraConnectionProperties(
                        baseUrl = "http://jira.example",
                        username = "user",
                        password = "pass",
                    ),
            )
        assertNotNull(config.atlassianJiraRestClient(props))
        assertNotNull(config.octopusJiraClient(props))
    }
}
