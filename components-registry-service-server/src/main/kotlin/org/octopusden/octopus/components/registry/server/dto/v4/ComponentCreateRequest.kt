package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern

/**
 * Create body for a new component. Mirrors the v2 schema row-for-row — no
 * `metadata: Map` catch-all. Top-level scalars map directly to `components`
 * columns; nested collections map to per-component child tables.
 *
 * Per-version configuration is split: the base row is supplied via
 * `baseConfiguration`; override rows are added afterwards via the
 * field-override API.
 *
 * **Strict contract** — `ComponentManagementService` rejects a payload with
 * **400 Bad Request** when `baseConfiguration` is null, or
 * `baseConfiguration.build` is null, or `baseConfiguration.build.buildSystem` is
 * null/blank. Although declared nullable for backward-compatible
 * deserialisation, every created component MUST carry a base build system; the
 * server is the source of truth.
 *
 * **`group` is NOT required and is NOT assigned via the API** (R1
 * aggregator/parentComponent decouple): a ComponentGroup is DSL aggregator
 * membership (a `components { }` owner + its sub-components), established only by
 * the migration/import path. A provided `group` here is accepted but IGNORED —
 * an API-created component is standalone (`componentGroup = null`).
 */
data class ComponentCreateRequest(
    val name: String,
    val displayName: String? = null,
    val componentOwner: String? = null,
    val productType: String? = null,
    // Single-value (see DTO KDoc): a component belongs to at most one system.
    // The legacy `systems: Set<String>` was widened to many during early DSL
    // import; business decision (post-#299) is exactly one. The column is
    // nullable so a component without a designated system stays expressible
    // until/if a strict-contract follow-up makes it required.
    val system: String? = null,
    val clientCode: String? = null,
    val solution: Boolean? = null,
    val parentComponentName: String? = null,
    // Whether this component may be referenced as a parent by others (parent-picker
    // eligibility). Normally seeded by import; accepted here so an admin can mark a
    // component can-be-parent. NOTE: this is NOT the same as an aggregator (a
    // `components { }` owner that forms a group — see `group`); the two are
    // independent. A component with `canBeParent = true` may not also set
    // `parentComponentName` (single-level: a parent cannot have a parent) — rejected.
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val canBeParent: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val archived: Boolean = false,
    // Ordered multi-value (first = primary); canonicalized server-side
    // (trim → drop blank → keep-first dedupe).
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val releaseManager: List<String> = emptyList(),
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val securityChampion: List<String> = emptyList(),
    val copyright: String? = null,
    val releasesInDefaultBranch: Boolean? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val labels: Set<String> = emptySet(),
    val jiraDisplayName: String? = null,
    val jiraHotfixVersionFormat: String? = null,
    val vcsExternalRegistry: String? = null,
    val distributionExplicit: Boolean? = null,
    val distributionExternal: Boolean? = null,
    // Accepted for backward compatibility but IGNORED: group membership is
    // migration-owned (DSL `components { }` aggregators), never assigned via the API.
    val group: ComponentGroupRequest? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val docs: List<DocLinkRequest> = emptyList(),
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val artifactIds: List<ArtifactIdRequest> = emptyList(),
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val securityGroups: List<SecurityGroupRequest> = emptyList(),
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val teamcityProjects: List<TeamcityProjectRequest> = emptyList(),
    // REQUIRED despite the nullable type: the server rejects a create whose baseConfiguration
    // (or its build.buildSystem) is missing with 400 (see class KDoc). No `description=` here —
    // baseConfiguration is a $ref, so OpenAPI 3.0 would hoist the description onto the shared
    // BaseConfigurationRequest schema, where it would wrongly appear on the (optional) Update use.
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val baseConfiguration: BaseConfigurationRequest? = null,
    // Optional change metadata recorded on the audit row (not on the component).
    // A blank/whitespace key is accepted as "no key" (normalized to null); a
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
