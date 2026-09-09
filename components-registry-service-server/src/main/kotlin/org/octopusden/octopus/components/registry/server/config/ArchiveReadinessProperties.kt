package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.time.Duration

@ConfigurationProperties(prefix = "archive-readiness")
class ArchiveReadinessProperties(
    val retiredJiraProjectCategories: Set<String> = emptySet(),
    @NestedConfigurationProperty
    val jira: JiraConnectionProperties = JiraConnectionProperties(),
    @NestedConfigurationProperty
    val vcsFacade: VcsFacadeConnectionProperties = VcsFacadeConnectionProperties(),
) {
    fun isJiraConfigured(): Boolean = jira.baseUrl.isNotBlank()

    data class JiraConnectionProperties(
        val baseUrl: String = "",
        val username: String = "",
        val password: String = "",
        /** Per-call HTTP connect timeout for the Jira issue-search client. */
        val connectTimeout: Duration = Duration.ofSeconds(5),
        /** Per-call HTTP read timeout — bounds how long an unresponsive Jira can pin a thread. */
        val readTimeout: Duration = Duration.ofSeconds(10),
    )

    data class VcsFacadeConnectionProperties(
        val baseUrl: String = "",
        val timeRetryInMillis: Int = 3000,
    )
}
