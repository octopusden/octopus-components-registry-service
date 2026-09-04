package org.octopusden.octopus.components.registry.server.jira

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.web.client.RestClient

/**
 * Minimal Jira REST client for archive-readiness's two needs: paging through an open-issue JQL
 * search ([searchJql]) and a lightweight authenticated call proving the configured credential is
 * live ([checkSession]).
 *
 * Built on Spring's own [RestClient] against Jira's REST API directly, rather than
 * `jira-rest-java-client`, which pulled a Jersey 2 client onto the classpath purely for these two
 * GET operations and repeatedly conflicted with Spring Cloud Netflix Eureka's own Jersey-presence
 * detection and `jakarta.ws.rs.ext.RuntimeDelegate` resolution (see build.gradle history).
 *
 * No explicit `fields` query parameter is ever sent — Jira's own default field set already
 * includes everything [JiraSearchIssueFields] reads (`summary`, `fixVersions`), and an incomplete
 * explicit allowlist broke in production before (a field `jira-rest-java-client-core`'s own parser
 * required unconditionally, "issuetype" then "created", was missing from it).
 */
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

    /** Throws on any non-2xx response or connection failure — used only to prove liveness. */
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
