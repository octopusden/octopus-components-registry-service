package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

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
    )

    data class VcsFacadeConnectionProperties(
        val baseUrl: String = "",
        val timeRetryInMillis: Int = 3000,
    )
}
