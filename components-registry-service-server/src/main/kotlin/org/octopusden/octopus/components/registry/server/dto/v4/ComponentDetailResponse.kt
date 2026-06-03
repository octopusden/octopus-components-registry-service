package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant
import java.util.UUID

/**
 * v4 detail-view of one component. Mirrors the v2 schema row-for-row — the
 * legacy `metadata: Map<String, Any?>` field is gone; every persisted value has
 * an explicit, typed field.
 *
 * `name` is the v1–v3-compatible alias for `components.component_key`
 * (schema-spec.md §5).
 *
 * `configurations` is a flat list of `component_configurations` rows — one
 * BASE row and N override rows (SCALAR_OVERRIDE / MARKER). Resolving against a
 * concrete version is the resolver's job; the editor view shows the rows
 * one-to-one with what the Portal edits.
 */
data class ComponentDetailResponse(
    val id: UUID,
    val name: String,
    val displayName: String?,
    val componentOwner: String?,
    val productType: String?,
    val system: String?,
    val clientCode: String?,
    val archived: Boolean,
    val solution: Boolean?,
    val parentComponentName: String?,
    val canBeParent: Boolean = false,
    val version: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    // Ordered multi-value (first = primary). Legacy v1/v2/v3 keep the
    // comma-joined String; v4 exposes the ordered list directly.
    val releaseManager: List<String> = emptyList(),
    val securityChampion: List<String> = emptyList(),
    val copyright: String? = null,
    val releasesInDefaultBranch: Boolean? = null,
    val labels: Set<String> = emptySet(),
    // Flat per-component scalars introduced by v2 schema —————————————————————
    val jiraDisplayName: String? = null,
    val jiraHotfixVersionFormat: String? = null,
    val vcsExternalRegistry: String? = null,
    val distributionExplicit: Boolean? = null,
    val distributionExternal: Boolean? = null,
    // Per-component child rows (never per-version-rangeable) ————————————————
    val group: ComponentGroupResponse? = null,
    val docs: List<DocLinkResponse> = emptyList(),
    val artifactIds: List<ArtifactIdResponse> = emptyList(),
    val securityGroups: List<SecurityGroupResponse> = emptyList(),
    val teamcityProjects: List<TeamcityProjectResponse> = emptyList(),
    // Configuration rows (base + overrides) ————————————————————————————————
    val configurations: List<ComponentConfigurationResponse> = emptyList(),
    // Per-user affordance: true when the CURRENT caller may edit this component
    // (is its componentOwner / releaseManager / securityChampion, or an admin).
    // Stamped by the controller from `PermissionEvaluator.canEditComponent` on
    // every detail-returning endpoint (GET, create, update) so the Portal can
    // hide the Edit affordances. Null when not evaluated (e.g. produced outside
    // a request context); the Portal then falls back to its global permission.
    val canEdit: Boolean? = null,
)
