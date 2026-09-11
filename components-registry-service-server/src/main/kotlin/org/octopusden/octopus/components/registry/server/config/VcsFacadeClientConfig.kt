package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.impl.ClassicVcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.impl.VcsFacadeClientParametersProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
