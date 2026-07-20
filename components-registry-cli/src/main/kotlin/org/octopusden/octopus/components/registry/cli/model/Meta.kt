package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.Serializable

// Meta endpoints under /rest/api/4/components/meta/*.
//
// Most meta + dictionary endpoints (build-systems, client-codes, escrow-generations, group-keys,
// java-versions, jira-project-keys, labels, labels/dictionary, maven-versions, owners,
// parent-component-names, repository-types, systems, systems/dictionary) return a bare JSON array of
// strings and therefore decode directly to List<String> — no dedicated DTO is needed for those.
//
// The endpoints below return object schemas and so are mirrored as data classes.

/**
 * Mirror of v4.json `EmployeeMatchResponse` — one element of the array returned by
 * GET /rest/api/4/components/meta/employees. Required: active, username.
 */
@Serializable
data class EmployeeMatchResponse(
    val username: String,
    val active: Boolean,
)

/**
 * Mirror of v4.json `EmployeeIntegrationHealthResponse` — body of
 * GET /rest/api/4/components/meta/employees/health. `status` is the enum UP|DOWN|DISABLED (String).
 * Required: status.
 */
@Serializable
data class EmployeeIntegrationHealthResponse(
    val status: String,
)

/**
 * Mirror of v4.json `ComponentEditorsResponse` — body of
 * GET /rest/api/4/components/{idOrName}/editors. Required: releaseManagers, securityChampions.
 * `manager` (SYS-063) is the componentOwner's manager per employee-service, null when the
 * owner has none / is unresolvable / employee-service is unavailable.
 */
@Serializable
data class ComponentEditorsResponse(
    val releaseManagers: List<String>,
    val securityChampions: List<String>,
    val componentOwner: String? = null,
    val manager: String? = null,
)
