package org.octopusden.octopus.components.registry.server.jira

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.web.client.RestClient

class JiraIssueSearchClient(
    private val restClient: RestClient,
) {
    fun searchJql(
        jql: String,
        startAt: Int,
        maxResults: Int,
    ): JiraSearchResponse =
        restClient
            .get()
            .uri { builder ->
                builder
                    .path("/rest/api/2/search")
                    .queryParam("jql", jql)
                    .queryParam("startAt", startAt)
                    .queryParam("maxResults", maxResults)
                    .build()
            }.retrieve()
            .body(JiraSearchResponse::class.java)
            ?: JiraSearchResponse()

    fun checkSession() {
        restClient
            .get()
            .uri("/rest/auth/1/session")
            .retrieve()
            .toBodilessEntity()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraSearchResponse(
    val total: Int = 0,
    val issues: List<JiraSearchIssue> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraSearchIssue(
    val key: String,
    val fields: JiraSearchIssueFields = JiraSearchIssueFields(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraSearchIssueFields(
    val summary: String? = null,
    val fixVersions: List<JiraFixVersionRef> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraFixVersionRef(
    val name: String? = null,
)
