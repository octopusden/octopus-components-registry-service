package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * CRS-A tri-state clear contract shared by every write-side aspect string
 * scalar (build/escrow/jira) and the top-level `vcsExternalRegistry`.
 */
const val V4_SCALAR_CLEAR_SEMANTICS: String =
    "Write tri-state: omit or null = leave unchanged; \"\" (or blank) = clear to null; " +
        "non-blank = set verbatim. On create, \"\" is treated as null."

/**
 * Description of the dedicated `skipCommitCheck` flag (Q12), replacing the legacy
 * `externalRegistry = "NOT_AVAILABLE"` sentinel in v4 storage.
 */
const val V4_SKIP_COMMIT_CHECK: String =
    "When true, commit checks are skipped at release/RC issue-assignment (issues bind to RCs by " +
        "builds only). Dedicated flag replacing the legacy externalRegistry=\"NOT_AVAILABLE\" sentinel; " +
        "legacy v1–v3 clients still see externalRegistry=\"NOT_AVAILABLE\" when this is true. Must be " +
        "false when the effective BASE build system is WHISKEY (rejected 422 otherwise)."

/**
 * One row of `component_configurations`, projected for the v4 editor view.
 *
 * Three editor-visible shapes per schema-spec.md §3 (Model A' override taxonomy):
 *
 *  - `BASE`: `overriddenAttribute == null`. Base scalars populated; all child
 *    collections (when non-empty) carry the component's default child rows.
 *
 *  - `SCALAR_OVERRIDE`: `overriddenAttribute` is one of ~30 known
 *    `aspect.field` paths. Exactly one aspect.field is non-null, mirroring the
 *    one typed column set in the row. No child collections.
 *
 *  - `MARKER`: `overriddenAttribute` is one of the six marker names. All scalar
 *    aspects are null; the matching child collection carries the replacement
 *    rows for the row's version range.
 *
 * A fourth storage-only row shape `RANGE_PRESENCE` exists on the entity but is
 * never serialised here — V4 mappers filter it out (it marks DSL-declared
 * ranges with no real override for resolver enumeration). The enum value
 * exists for completeness so audit/admin tooling can reference it.
 *
 * Resolution (merging base + overrides for a concrete version) is the
 * resolver's job — v4 deliberately exposes rows individually so the Portal can
 * edit them one-to-one.
 */
data class ComponentConfigurationResponse(
    val id: UUID,
    val versionRange: String,
    val rowType: ConfigurationRowType,
    val overriddenAttribute: String?,
    val isSyntheticBase: Boolean,
    val build: BuildAspectResponse? = null,
    val escrow: EscrowAspectResponse? = null,
    val jira: JiraAspectResponse? = null,
    val vcsEntries: List<VcsEntryResponse> = emptyList(),
    val mavenArtifacts: List<MavenArtifactResponse> = emptyList(),
    val fileUrlArtifacts: List<FileUrlArtifactResponse> = emptyList(),
    val dockerImages: List<DockerImageResponse> = emptyList(),
    val packages: List<PackageResponse> = emptyList(),
    val requiredTools: List<String> = emptyList(),
    val buildToolBeans: List<BuildToolBeanResponse> = emptyList(),
)

enum class ConfigurationRowType {
    BASE,
    SCALAR_OVERRIDE,
    MARKER,

    /**
     * Storage-only row: marks a DSL `componentVersion(R)` block whose
     * scalars/markers all match base. Resolver enumerates the range; V4
     * editor APIs and field-override mutations exclude these rows. Never
     * appears in `ComponentConfigurationResponse` or `FieldOverrideResponse`
     * payloads — the enum value exists only for entity-side classification
     * and admin/internal tooling.
     */
    RANGE_PRESENCE,
}

data class BuildAspectResponse(
    val buildSystem: String? = null,
    val javaVersion: String? = null,
    val mavenVersion: String? = null,
    val gradleVersion: String? = null,
    val buildFilePath: String? = null,
    val deprecated: Boolean? = null,
    val requiredProject: Boolean? = null,
    val projectVersion: String? = null,
    val systemProperties: String? = null,
    val buildTasks: String? = null,
)

data class EscrowAspectResponse(
    val providedDependencies: String? = null,
    val reusable: Boolean? = null,
    val generation: String? = null,
    val diskSpace: String? = null,
    val additionalSources: String? = null,
    val gradleIncludeConfigurations: String? = null,
    val gradleExcludeConfigurations: String? = null,
    val gradleIncludeTestConfigurations: Boolean? = null,
    val buildTask: String? = null,
)

data class JiraAspectResponse(
    val projectKey: String? = null,
    val technical: Boolean? = null,
    val minorVersionFormat: String? = null,
    val releaseVersionFormat: String? = null,
    val buildVersionFormat: String? = null,
    val lineVersionFormat: String? = null,
    val versionPrefix: String? = null,
    val versionFormat: String? = null,
    /**
     * Per-range override for `componentVersionFormat.hotfixVersionFormat`.
     * `null` here means "no per-range override for this range" — consumers
     * MUST fall back to the per-component base on
     * `ComponentDetailResponse.jiraHotfixVersionFormat` (which is itself
     * inherited from `Defaults.groovy` if the DSL declared none).
     */
    val hotfixVersionFormat: String? = null,
)

data class VcsEntryResponse(
    val id: UUID,
    val name: String? = null,
    val vcsPath: String,
    val branch: String? = null,
    val tag: String? = null,
    val hotfixBranch: String? = null,
    val repositoryType: String? = null,
    val sortOrder: Int,
)

data class MavenArtifactResponse(
    val id: UUID,
    val groupPattern: String,
    val artifactPattern: String,
    val extension: String? = null,
    val classifier: String? = null,
    val sortOrder: Int,
)

data class FileUrlArtifactResponse(
    val id: UUID,
    val url: String,
    val artifactId: String? = null,
    val classifier: String? = null,
    val sortOrder: Int,
)

data class DockerImageResponse(
    val id: UUID,
    val imageName: String,
    val flavor: String? = null,
    val sortOrder: Int,
)

data class PackageResponse(
    val id: UUID,
    val packageType: String,
    val packageName: String,
    val sortOrder: Int,
)

// ----------------------------------------------------------------------------
// Write side — shared by ComponentCreate/UpdateRequest base-configuration body
// and FieldOverrideCreate/UpdateRequest marker children payload.
// ----------------------------------------------------------------------------

/**
 * Patch / create body for the BASE row of a component. Used on
 * `ComponentCreateRequest.baseConfiguration` (full body, scalars optional;
 * server defaults `versionRange` to `(,0),[0,)` when missing) and on
 * `ComponentUpdateRequest.baseConfiguration` (PATCH; null scalars mean
 * "don't touch"; present child lists REPLACE).
 */
data class BaseConfigurationRequest(
    val versionRange: String? = null,
    val build: BuildAspectRequest? = null,
    val escrow: EscrowAspectRequest? = null,
    val jira: JiraAspectRequest? = null,
    val vcsEntries: List<VcsEntryRequest>? = null,
    val mavenArtifacts: List<MavenArtifactRequest>? = null,
    val fileUrlArtifacts: List<FileUrlArtifactRequest>? = null,
    val dockerImages: List<DockerImageRequest>? = null,
    val packages: List<PackageRequest>? = null,
    val requiredTools: List<String>? = null,
    val buildToolBeans: List<BuildToolBeanRequest>? = null,
)

data class BuildAspectRequest(
    // buildSystem is a required, validated enum — not subject to the clear rule.
    val buildSystem: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val javaVersion: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val mavenVersion: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val gradleVersion: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val buildFilePath: String? = null,
    val deprecated: Boolean? = null,
    val requiredProject: Boolean? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val projectVersion: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val systemProperties: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val buildTasks: String? = null,
)

data class EscrowAspectRequest(
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val providedDependencies: String? = null,
    val reusable: Boolean? = null,
    // generation is a validated enum mode — not subject to the clear rule.
    val generation: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val diskSpace: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val additionalSources: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val gradleIncludeConfigurations: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val gradleExcludeConfigurations: String? = null,
    val gradleIncludeTestConfigurations: Boolean? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val buildTask: String? = null,
)

data class JiraAspectRequest(
    @field:Schema(
        description = "$V4_SCALAR_CLEAR_SEMANTICS Clearing it removes the component's Jira association " +
            "(no Jira version coordinates are computed) and drops its (projectKey, versionPrefix) uniqueness claim.",
    )
    val projectKey: String? = null,
    val technical: Boolean? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val minorVersionFormat: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val releaseVersionFormat: String? = null,
    @field:Schema(
        description = "$V4_SCALAR_CLEAR_SEMANTICS When cleared, the build version format falls back to the release version format.",
    )
    val buildVersionFormat: String? = null,
    @field:Schema(
        description = "$V4_SCALAR_CLEAR_SEMANTICS When cleared, the line version format falls back to the minor version format.",
    )
    val lineVersionFormat: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val versionPrefix: String? = null,
    @field:Schema(description = V4_SCALAR_CLEAR_SEMANTICS)
    val versionFormat: String? = null,
    // Intentionally NOT exposing per-range hotfixVersionFormat as a V4
    // editor-write field — DSL import is currently the only producer in
    // production and there's no UI for per-range hotfix overrides. The
    // applyScalarValue plumbing on the entity side is available to
    // a follow-up ticket if/when a UI need surfaces. Read-side exposure
    // (`JiraAspectResponse.hotfixVersionFormat`) is unaffected.
)

data class VcsEntryRequest(
    val name: String? = null,
    val vcsPath: String,
    val branch: String? = null,
    val tag: String? = null,
    val hotfixBranch: String? = null,
    val repositoryType: String? = null,
)

data class MavenArtifactRequest(
    val groupPattern: String,
    val artifactPattern: String,
    val extension: String? = null,
    val classifier: String? = null,
)

data class FileUrlArtifactRequest(
    val url: String,
    val artifactId: String? = null,
    val classifier: String? = null,
)

data class DockerImageRequest(
    val imageName: String,
    val flavor: String? = null,
)

data class PackageRequest(
    val packageType: String,
    val packageName: String,
)

/**
 * Request DTO for one build-tool bean in a `build.buildTools` marker or BASE
 * row payload. `edition` is valid only for `beanType = "oracleDatabase"`.
 * Valid `beanType` values: `oracleDatabase`, `cProduct`, `kProduct`, `dProduct`,
 * `dDbProduct`, `odbc`.
 */
data class BuildToolBeanRequest(
    val beanType: String,
    val toolType: String? = null,
    val settingsProperty: String? = null,
    val versionPattern: String? = null,
    val edition: String? = null,
)

/**
 * Response DTO for one persisted build-tool bean row. Includes `id` and
 * `sortOrder` for client-side row-level operations (mirrors `MavenArtifactResponse`).
 */
data class BuildToolBeanResponse(
    val id: UUID,
    val beanType: String,
    val toolType: String? = null,
    val settingsProperty: String? = null,
    val versionPattern: String? = null,
    val edition: String? = null,
    val sortOrder: Int,
)

// ----------------------------------------------------------------------------
// Top-level per-component child rows (never per-version-rangeable).
// ----------------------------------------------------------------------------

data class ComponentGroupResponse(
    val groupKey: String,
    val isFake: Boolean,
    val role: ComponentGroupRole,
)

enum class ComponentGroupRole {
    /** This component's row is itself the aggregator (own `componentKey == groupKey`). */
    AGGREGATOR,

    /** This component belongs to the group but is not the aggregator. */
    MEMBER,
}

data class ComponentGroupRequest(
    val groupKey: String,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val isFake: Boolean = false,
)

data class DocLinkResponse(
    val id: UUID,
    val docComponentKey: String,
    val majorVersion: String? = null,
    val sortOrder: Int,
)

data class DocLinkRequest(
    val docComponentKey: String,
    val majorVersion: String? = null,
)

/**
 * One artifact-ownership mapping (response). `versionRange` is `null`/ALL_VERSIONS for the base
 * mapping or the override range; `mode` is EXPLICIT | ALL_EXCEPT_CLAIMED | ALL; `artifactTokens`
 * holds literal tokens for EXPLICIT (empty otherwise). [legacyArtifactIdPattern] is server-computed
 * read-only — the rendered legacy v1–v3 pattern for the UI preview (ALL_EXCEPT needs cross-component
 * siblings the client lacks).
 */
data class ArtifactIdResponse(
    val id: UUID,
    val versionRange: String?,
    val groupPattern: String,
    val mode: String,
    val artifactTokens: List<String> = emptyList(),
    val legacyArtifactIdPattern: String? = null,
)

/**
 * One artifact-ownership mapping (write). `versionRange` null ⇒ base (ALL_VERSIONS). Invariants
 * (enforced 400): EXPLICIT requires ≥1 token; ALL / ALL_EXCEPT_CLAIMED carry zero tokens;
 * ALL_EXCEPT_CLAIMED is single-group. No `legacyArtifactIdPattern` on input (server-computed).
 */
data class ArtifactIdRequest(
    val versionRange: String? = null,
    val groupPattern: String,
    val mode: String? = null,
    val artifactTokens: List<String> = emptyList(),
)

data class SecurityGroupResponse(
    val id: UUID,
    val groupType: String,
    val groupName: String,
)

data class SecurityGroupRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, defaultValue = "read")
    val groupType: String = "read",
    val groupName: String,
)

data class TeamcityProjectResponse(
    val id: UUID,
    val projectId: String,
    val projectUrl: String? = null,
    val sortOrder: Int,
    // TC PROJECT_VERSION line this project belongs to; null when the project declares none.
    val projectVersion: String? = null,
)

data class TeamcityProjectRequest(
    val projectId: String,
)
