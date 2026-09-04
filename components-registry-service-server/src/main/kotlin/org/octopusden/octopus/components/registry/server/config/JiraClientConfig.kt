package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.components.registry.server.jira.JiraIssueSearchClient
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.jira.JiraClassicClient
import org.octopusden.octopus.infrastructure.jira.JiraClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.support.BasicAuthenticationInterceptor
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(ArchiveReadinessProperties::class)
class JiraClientConfig {
    @Bean
    fun jiraIssueSearchClient(props: ArchiveReadinessProperties): JiraIssueSearchClient? =
        if (props.isJiraConfigured()) {
            val restClient =
                RestClient
                    .builder()
                    .baseUrl(props.jira.baseUrl)
                    .requestInterceptor(BasicAuthenticationInterceptor(props.jira.username, props.jira.password))
                    .build()
            JiraIssueSearchClient(restClient)
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
