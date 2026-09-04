package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.impl.ClassicVcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.impl.VcsFacadeClientParametersProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides the (nullable) `VcsFacadeClient` bean
 * [org.octopusden.octopus.components.registry.server.service.archivereadiness.RepositoryChecker]
 * and [org.octopusden.octopus.components.registry.server.service.archivereadiness.LivenessProbe]
 * depend on. `null` when `archive-readiness.vcs-facade.base-url` is blank — this codebase's "VCS
 * unconfigured" signal (see [ArchiveReadinessProperties]) — mirroring the nullable
 * `JiraIssueSearchClient`/`JiraClient` beans [JiraClientConfig] already produces for the same
 * reason, rather than eagerly constructing a real client against a blank base URL.
 */
@Configuration
class VcsFacadeClientConfig {
    @Bean
    fun vcsFacadeClient(properties: ArchiveReadinessProperties): VcsFacadeClient? =
        if (properties.vcsFacade.baseUrl.isBlank()) {
            null
        } else {
            ClassicVcsFacadeClient(
                object : VcsFacadeClientParametersProvider {
                    override fun getApiUrl(): String = properties.vcsFacade.baseUrl

                    override fun getTimeRetryInMillis(): Int = properties.vcsFacade.timeRetryInMillis
                },
            )
        }
}
