package org.octopusden.octopus.components.registry.server.config

import com.atlassian.jira.rest.client.api.JiraRestClient
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.jira.JiraClassicClient
import org.octopusden.octopus.infrastructure.jira.JiraClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

@Configuration
@EnableConfigurationProperties(ArchiveReadinessProperties::class)
class JiraClientConfig {
    @Bean
    fun atlassianJiraRestClient(props: ArchiveReadinessProperties): JiraRestClient? =
        if (props.isJiraConfigured()) {
            AsynchronousJiraRestClientFactory().createWithBasicHttpAuthentication(
                URI(props.jira.baseUrl),
                props.jira.username,
                props.jira.password,
            )
        } else {
            null
        }

    @Bean
    fun octopusJiraClient(props: ArchiveReadinessProperties): JiraClient? =
        if (props.isJiraConfigured()) {
            JiraClassicClient(
                object : ClientParametersProvider {
                    override fun getApiUrl(): String = props.jira.baseUrl

                    override fun getAuth(): CredentialProvider =
                        StandardBasicCredCredentialProvider(props.jira.username, props.jira.password)
                },
            )
        } else {
            null
        }
}
