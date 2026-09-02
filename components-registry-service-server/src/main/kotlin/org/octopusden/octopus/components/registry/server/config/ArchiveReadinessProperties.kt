package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

@ConfigurationProperties(prefix = "archive-readiness")
class ArchiveReadinessProperties(
    val retiredJiraProjectCategories: Set<String> = emptySet(),
    @NestedConfigurationProperty
    val jira: JiraConnectionProperties = JiraConnectionProperties(),
    // VcsFacadeClient (the vcs-facade client bean) is now constructed lazily — see
    // VcsFacadeClientConfig / LivenessProbe's class kdoc — so a blank baseUrl here is safe: no
    // Feign target is built until something actually invokes the client, mirroring the
    // Jira/TeamCity baseUrl defaults below and giving VCS the same "blank = unconfigured, no
    // entries" off-switch they already have.
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
