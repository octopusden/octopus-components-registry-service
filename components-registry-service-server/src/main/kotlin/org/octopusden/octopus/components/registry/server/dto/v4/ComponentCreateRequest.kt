package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * Create body for a new component. Mirrors the v2 schema row-for-row — no
 * `metadata: Map` catch-all. Top-level scalars map directly to `components`
 * columns; nested collections map to per-component child tables.
 *
 * Per-version configuration is split: the base row is supplied via
 * `baseConfiguration` (defaults to a base row covering all versions when
 * omitted); override rows are added afterwards via the field-override API.
 */
data class ComponentCreateRequest(
    val name: String,
    val displayName: String? = null,
    val componentOwner: String? = null,
    val productType: String? = null,
    val systems: Set<String> = emptySet(),
    val clientCode: String? = null,
    val solution: Boolean? = null,
    val parentComponentName: String? = null,
    val archived: Boolean = false,
    val releaseManager: String? = null,
    val securityChampion: String? = null,
    val copyright: String? = null,
    val releasesInDefaultBranch: Boolean? = null,
    val labels: Set<String> = emptySet(),
    val jiraDisplayName: String? = null,
    val jiraHotfixVersionFormat: String? = null,
    val vcsExternalRegistry: String? = null,
    val distributionExplicit: Boolean? = null,
    val distributionExternal: Boolean? = null,
    val group: ComponentGroupRequest? = null,
    val docs: List<DocLinkRequest> = emptyList(),
    val artifactIds: List<ArtifactIdRequest> = emptyList(),
    val securityGroups: List<SecurityGroupRequest> = emptyList(),
    val teamcityProjects: List<TeamcityProjectRequest> = emptyList(),
    val baseConfiguration: BaseConfigurationRequest? = null,
)
