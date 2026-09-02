package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

@ConfigurationProperties(prefix = "archive-readiness")
class ArchiveReadinessProperties(
    val retiredJiraProjectCategories: Set<String> = emptySet(),
    @NestedConfigurationProperty
    val jira: JiraConnectionProperties = JiraConnectionProperties(),
    // vcs-facade has no configured/unconfigured concept anywhere in this codebase (see
    // LivenessProbe's class kdoc): VcsFacadeClient is a plain non-nullable dependency of
    // RepositoryChecker, unlike the nullable Jira client beans above, so a real bean must
    // exist even when no real vcs-facade endpoint has been deployed. Unlike the Jira/TeamCity
    // clients, Feign's HardCodedTarget rejects a BLANK base URL at construction time (not just
    // at call time) — `Util.emptyToNull` + `checkNotNull` throw NPE on `target(type, "")` — so
    // the default here can't be "" like the other clients' baseUrl defaults. `.invalid` is the
    // RFC 2606 reserved TLD guaranteed to never resolve: a deployment that hasn't set a real
    // `archive-readiness.vcs-facade.base-url` gets a client that fails fast (unknown host) on
    // first real call, caught by RepositoryChecker's own catch-all → UNKNOWN, same degraded
    // behaviour as a blank TeamCity/Jira baseUrl produces for their checkers.
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
        val baseUrl: String = "http://vcs-facade.invalid",
        val timeRetryInMillis: Int = 3000,
    )
}
