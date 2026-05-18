package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * Patch body (JSON Merge Patch semantics): null scalar = "don't touch";
 * present collection = REPLACE. `version` is the optimistic-lock value held by
 * the client; mismatch → 409.
 *
 * `baseConfiguration` (when present) is also patched in-place with the same
 * rules — null aspect fields preserve, present child lists replace. Override
 * rows are managed via the field-override API, not from here.
 */
data class ComponentUpdateRequest(
    val version: Long,
    val name: String? = null,
    val displayName: String? = null,
    val componentOwner: String? = null,
    val productType: String? = null,
    val systems: Set<String>? = null,
    val clientCode: String? = null,
    val solution: Boolean? = null,
    val parentComponentName: String? = null,
    val archived: Boolean? = null,
    val releaseManager: String? = null,
    val securityChampion: String? = null,
    val copyright: String? = null,
    val releasesInDefaultBranch: Boolean? = null,
    val labels: Set<String>? = null,
    val jiraDisplayName: String? = null,
    val jiraHotfixVersionFormat: String? = null,
    val vcsExternalRegistry: String? = null,
    val distributionExplicit: Boolean? = null,
    val distributionExternal: Boolean? = null,
    val group: ComponentGroupRequest? = null,
    val clearGroup: Boolean = false,
    val docs: List<DocLinkRequest>? = null,
    val artifactIds: List<ArtifactIdRequest>? = null,
    val securityGroups: List<SecurityGroupRequest>? = null,
    val teamcityProjects: List<TeamcityProjectRequest>? = null,
    val baseConfiguration: BaseConfigurationRequest? = null,
)
