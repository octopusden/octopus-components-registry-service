package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * The people who may edit a component: its `componentOwner`, ordered `releaseManagers`
 * (first = primary), and ordered `securityChampions`. These — together with administrators
 * (holders of `EDIT_ANY_COMPONENT`) — are exactly who passes
 * [PermissionEvaluator.canEditComponent][org.octopusden.octopus.components.registry.server.security.PermissionEvaluator.canEditComponent].
 *
 * Read-only informational projection for the Portal's "who can edit" surface; it carries no
 * authorization decision of its own (the per-component edit gate is enforced server-side on
 * the write endpoints). Administrators are intentionally NOT enumerated here — that is a
 * Keycloak realm-role, not per-component data.
 */
data class ComponentEditorsResponse(
    val componentOwner: String?,
    val releaseManagers: List<String> = emptyList(),
    val securityChampions: List<String> = emptyList(),
)
