package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant
import java.util.UUID

/**
 * Create body for one override row in `component_configurations`. The request
 * is a tagged union keyed on `overriddenAttribute`:
 *
 *  - **Scalar override** — `overriddenAttribute` is one of ~30 known
 *    `aspect.field` paths (e.g., `build.javaVersion`, `escrow.reusable`,
 *    `jira.projectKey`). `value` must be a JSON primitive (string, number,
 *    boolean) matching the column type; `markerChildren` must be null.
 *
 *  - **Marker override** — `overriddenAttribute` is one of the six marker
 *    names (`vcs.settings`, `distribution.maven`, `distribution.fileUrl`,
 *    `distribution.docker`, `distribution.packages`, `build.requiredTools`).
 *    `markerChildren` carries the full replacement child collection for the
 *    family the marker addresses; `value` must be null.
 *
 * Use DELETE `/field-overrides/{id}` to remove an override — `value = null`
 * is rejected on create.
 */
data class FieldOverrideCreateRequest(
    val overriddenAttribute: String,
    val versionRange: String,
    val value: Any? = null,
    val markerChildren: MarkerChildrenPayload? = null,
)

data class FieldOverrideUpdateRequest(
    val versionRange: String? = null,
    /**
     * New scalar value for this override row. **null = don't touch this field (PATCH semantic).**
     *
     * `FieldOverrideUpdateRequest.value: Any? = null` cannot distinguish an omitted JSON field
     * from an explicit JSON `null` at the Jackson layer without a presence-aware wrapper such as
     * `JsonNullable<Any>`. Introducing that wrapper is V4 ergonomics work tracked as a separate
     * tech-debt item. Until then, null here is silently treated as "no-op" — the row's typed
     * column is left unchanged.
     *
     * To clear an override entirely, use `DELETE /field-overrides/{id}`.
     * To set an explicit scalar value, pass the value here.
     *
     * **There is currently no V4 way to create a null-clearing scalar override row via PUT.**
     * Null-clearing rows are import-only (the Groovy DSL import pipeline emits them when a
     * DSL override range sets a scalar to null, clearing an inherited base value). See
     * `ConfigurationRowAccessors.kt` for the full null-handling contract across all four paths.
     */
    val value: Any? = null,
    val markerChildren: MarkerChildrenPayload? = null,
)

data class FieldOverrideResponse(
    val id: UUID,
    val overriddenAttribute: String,
    val versionRange: String,
    val rowType: ConfigurationRowType,
    val value: Any? = null,
    val markerChildren: MarkerChildrenPayload? = null,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

/**
 * Child rows attached to a marker override. Exactly one field is non-null,
 * matching the marker name on the parent row:
 *
 *  - `vcs.settings` → `vcsEntries`
 *  - `distribution.maven` → `mavenArtifacts`
 *  - `distribution.fileUrl` → `fileUrlArtifacts`
 *  - `distribution.docker` → `dockerImages`
 *  - `distribution.packages` → `packages`
 *  - `build.requiredTools` → `requiredTools` (list of tool names)
 */
data class MarkerChildrenPayload(
    val vcsEntries: List<VcsEntryRequest>? = null,
    val mavenArtifacts: List<MavenArtifactRequest>? = null,
    val fileUrlArtifacts: List<FileUrlArtifactRequest>? = null,
    val dockerImages: List<DockerImageRequest>? = null,
    val packages: List<PackageRequest>? = null,
    val requiredTools: List<String>? = null,
)
