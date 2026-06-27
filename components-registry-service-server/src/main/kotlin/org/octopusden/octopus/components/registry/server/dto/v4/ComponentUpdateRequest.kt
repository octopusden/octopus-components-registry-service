package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern

/**
 * Patch body (JSON Merge Patch semantics): null scalar = "don't touch";
 * present collection = REPLACE. `version` is the optimistic-lock value held by
 * the client; mismatch → 409.
 *
 * `baseConfiguration` (when present) is also patched in-place with the same
 * rules — null aspect fields preserve, present child lists replace. Override
 * rows are managed via the field-override API, not from here.
 *
 * **`group` is migration-owned and is NEVER modified via PATCH** (R1
 * aggregator/parentComponent decouple): a ComponentGroup is DSL aggregator
 * membership, established only by the migration/import path. On PATCH the group is
 * left untouched regardless of input — a provided `group` is accepted but IGNORED,
 * and `clearGroup` (true or false) is an accepted no-op. Both are kept on the wire
 * for backward compatibility (the frontend's `buildUpdateRequest` still emits
 * `clearGroup: false`).
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
    // Both accepted for backward compatibility but IGNORED (no-op): group membership
    // is migration-owned and never modified via the API (see class KDoc).
    val group: ComponentGroupRequest? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val clearGroup: Boolean = false,
    // Explicit "remove the parent" signal. `parentComponentName == null` means
    // "don't touch" (JSON Merge Patch), so clearing a parent needs its own flag.
    // Used to remediate a grandfathered parent-of-parent row: clearing the
    // parent of a `canBeParent` component re-derives its group to its own key.
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val clearParent: Boolean = false,
    val docs: List<DocLinkRequest>? = null,
    val artifactIds: List<ArtifactIdRequest>? = null,
    val securityGroups: List<SecurityGroupRequest>? = null,
    val teamcityProjects: List<TeamcityProjectRequest>? = null,
    val baseConfiguration: BaseConfigurationRequest? = null,
    // Optional change metadata recorded on the audit row (not on the component).
    // These are change-scoped, not part of the component's patchable state: a
    // blank/whitespace key is accepted as "no key" (normalized to null), and a
    // non-blank key must match the Jira key format. See JIRA_TASK_KEY_PATTERN.
    @field:Pattern(
        regexp = JIRA_TASK_KEY_PATTERN,
        message = "must be a Jira task key like ABC-123",
    )
    @field:Schema(
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        description = "Optional Jira task key motivating the change (e.g. ABC-123); recorded on the audit row.",
    )
    val jiraTaskKey: String? = null,
    @field:Schema(
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        description = "Optional free-text comment describing the change; recorded on the audit row.",
    )
    val changeComment: String? = null,
)
