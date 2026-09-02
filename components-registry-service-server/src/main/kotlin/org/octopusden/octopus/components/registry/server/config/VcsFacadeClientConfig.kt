package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.impl.ClassicVcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.impl.VcsFacadeClientParametersProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides the `VcsFacadeClient` bean [org.octopusden.octopus.components.registry.server.service.archivereadiness.RepositoryChecker]
 * depends on. This was never wired when `RepositoryChecker` was introduced — no
 * `@SpringBootTest` exercised the full context until the archive-readiness controller test was
 * added, which is why the gap went unnoticed: every earlier archive-readiness task was covered
 * only by mock-based unit tests.
 *
 * Mirrors [JiraClientConfig] / `TeamcityClientConfig`'s `ClientParametersProvider` pattern
 * (`VcsFacadeClientParametersProvider` is the vcs-facade client library's equivalent). Unlike
 * the Jira beans, this one is unconditional/non-nullable — see
 * [ArchiveReadinessProperties.VcsFacadeConnectionProperties]'s kdoc for why.
 */
@Configuration
class VcsFacadeClientConfig {
    @Bean
    fun vcsFacadeClient(properties: ArchiveReadinessProperties): VcsFacadeClient =
        ClassicVcsFacadeClient(
            object : VcsFacadeClientParametersProvider {
                override fun getApiUrl(): String = properties.vcsFacade.baseUrl

                override fun getTimeRetryInMillis(): Int = properties.vcsFacade.timeRetryInMillis
            },
        )
}
