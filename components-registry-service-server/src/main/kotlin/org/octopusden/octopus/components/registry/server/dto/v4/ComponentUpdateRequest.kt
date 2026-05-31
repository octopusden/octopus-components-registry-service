package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * Patch body (JSON Merge Patch semantics): null scalar = "don't touch";
 * present collection = REPLACE. `version` is the optimistic-lock value held by
 * the client; mismatch → 409.
 *
 * `baseConfiguration` (when present) is also patched in-place with the same
 * rules — null aspect fields preserve, present child lists replace. Override
 * rows are managed via the field-override API, not from here.
 *
 * **Strict contract (UI-swift-sloth)** — `ComponentManagementService`
 * rejects PATCH payloads with **400 Bad Request** when:
 *  - `clearGroup == true` — every component must belong to a group, so
 *    clearing the group via PATCH is no longer expressible. The frontend's
 *    `buildUpdateRequest` always emits `clearGroup: false`.
 *  - `group` is non-null with a blank `group.groupKey` — same blank-key
 *    invariant as create.
 *
 * `group == null` continues to mean "don't touch" (Jackson cannot
 * distinguish field-absent from explicit-null without a presence-preserving
 * DTO, and every untouched-group PATCH today carries `group == null`).
 */
data class ComponentUpdateRequest(
    val version: Long,
    val name: String? = null,
    val displayName: String? = null,
    val componentOwner: String? = null,
    val productType: String? = null,
    // Single-value (see Create DTO). PATCH semantic: null = "don't touch"
    // (matches the rest of this PATCH body — Jackson cannot distinguish
    // field-absent from explicit-null without a presence-preserving DTO).
    // Clearing the system requires an explicit follow-up flag (e.g.
    // `clearSystem: Boolean`); not in scope here because the original
    // spec did not request it.
    val system: String? = null,
    val clientCode: String? = null,
    val solution: Boolean? = null,
    val parentComponentName: String? = null,
    // PATCH: null = "don't touch". Setting true on a component that has a parent,
    // or false while children still reference it, is rejected by the service.
    val canBeParent: Boolean? = null,
    val archived: Boolean? = null,
    // Ordered multi-value (first = primary). PATCH: null = "don't touch";
    // a provided list (including empty = clear) replaces the whole ordered list.
    val releaseManager: List<String>? = null,
    val securityChampion: List<String>? = null,
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
    // Explicit "remove the parent" signal. `parentComponentName == null` means
    // "don't touch" (JSON Merge Patch), so clearing a parent needs its own flag.
    // Used to remediate a grandfathered parent-of-parent row: clearing the
    // parent of a `canBeParent` component re-derives its group to its own key.
    val clearParent: Boolean = false,
    val docs: List<DocLinkRequest>? = null,
    val artifactIds: List<ArtifactIdRequest>? = null,
    val securityGroups: List<SecurityGroupRequest>? = null,
    val teamcityProjects: List<TeamcityProjectRequest>? = null,
    val baseConfiguration: BaseConfigurationRequest? = null,
)
