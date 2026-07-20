package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * The people who may edit a component: its `componentOwner`, ordered `releaseManagers`
 * (first = primary), ordered `securityChampions`, and (SYS-063) the `componentOwner`'s
 * `manager`, resolved through employee-service. These — together with administrators
 * (holders of `EDIT_ANY_COMPONENT`) — are exactly who passes
 * [PermissionEvaluator.canEditComponent][org.octopusden.octopus.components.registry.server.security.PermissionEvaluator.canEditComponent].
 *
 * Read-only informational projection for the Portal's "who can edit" surface; it carries no
 * authorization decision of its own (the per-component edit gate is enforced server-side on
 * the write endpoints). Administrators are still intentionally NOT enumerated here — that is
 * an open-ended Keycloak realm-role, not per-component data — but the owner's manager IS,
 * since (unlike "any admin") it resolves to a single concrete person for this component.
 * `manager` is null when the owner has none, is unresolvable, or employee-service is
 * unavailable/disabled — the same fail-closed-for-editing, fail-open-for-display null that
 * [EmployeeDirectoryService.getManager][org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService.getManager]
 * returns.
 */
data class ComponentEditorsResponse(
    val componentOwner: String?,
    val releaseManagers: List<String> = emptyList(),
    val securityChampions: List<String> = emptyList(),
    val manager: String? = null,
)
