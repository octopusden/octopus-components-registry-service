package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity

/**
 * Catalog of the scalar `aspect.field` paths backed by typed columns on
 * `component_configurations`. Used by `V4Mappers` (entity → DTO) and by
 * `ComponentManagementServiceImpl` (DTO → entity on field-override CRUD).
 *
 * The mapping is symmetric with the `applyScalarOverride` switch in
 * `EntityMappers.kt` so the resolver's merge view and the v4 editor view stay
 * in lock-step. Adding a new column requires three edits: the entity, this
 * file (both `extractScalarValue` and `applyScalarValue`), and the resolver's
 * `applyScalarOverride` table.
 *
 * ## Null-handling contract across the four code paths
 *
 * Null values on scalar columns have different semantics depending on the path:
 *
 * | Path | File | Null-handling |
 * |------|------|---------------|
 * | Groovy import (write) | `ImportServiceImpl.collectScalarDiffs` + `applyScalarValueToRow` | Emits null-valued SCALAR_OVERRIDE rows when a DSL override range clears a base scalar (null = "explicit clear"). |
 * | Resolver merge (read) | `EntityMappers.applyScalarOverride` | Unconditional assignment per discriminator — null column on a SCALAR_OVERRIDE row clears the merged value for the range (symmetric with import). |
 * | V4 POST create | `applyScalarValue` (this file, line ~115) | **Rejects null** with "use DELETE /field-overrides/{id} to remove the override". Creating a null-clear override via V4 is not supported — import is the only path. |
 * | V4 PUT update | `ComponentManagementServiceImpl.updateFieldOverride` | **No-op when null** (PATCH semantic). `FieldOverrideUpdateRequest.value: Any? = null` cannot distinguish omitted from explicit JSON null at the Jackson layer. See KDoc on `FieldOverrideUpdateRequest.value`. |
 *
 * The V4 GET path (`extractScalarValue`) returns whatever the column holds — null for
 * import-created null-clear rows, a typed value otherwise.
 */

/** All scalar `aspect.field` paths supported by `component_configurations`. */
internal val SCALAR_ATTRIBUTE_PATHS: Set<String> =
    setOf(
        "build.buildSystem",
        "build.buildSystemVersion",
        "build.javaVersion",
        "build.mavenVersion",
        "build.gradleVersion",
        "build.buildFilePath",
        "build.deprecated",
        "build.requiredProject",
        "build.projectVersion",
        "build.systemProperties",
        "build.buildTasks",
        "escrow.providedDependencies",
        "escrow.reusable",
        "escrow.generation",
        "escrow.diskSpace",
        "escrow.additionalSources",
        "escrow.gradleIncludeConfigurations",
        "escrow.gradleExcludeConfigurations",
        "escrow.gradleIncludeTestConfigurations",
        "escrow.buildTask",
        "jira.projectKey",
        "jira.technical",
        "jira.majorVersionFormat",
        "jira.releaseVersionFormat",
        "jira.buildVersionFormat",
        "jira.lineVersionFormat",
        "jira.versionPrefix",
        "jira.versionFormat",
        "jira.hotfixVersionFormat",
        "jira.displayName",
        "vcs.externalRegistry",
    )

/**
 * Read the single typed column corresponding to `overriddenAttribute` on a
 * SCALAR_OVERRIDE row. Returns null if the path is unknown or the column is
 * NULL.
 *
 * **Asymmetry with the resolver:** `EntityMappers.applyScalarOverride` uses
 * `else -> Unit` for unknown paths — forward-compat tolerance on the read
 * side, in case a new column is added to the entity ahead of the resolver
 * being aware of it. `applyScalarValue` below takes the opposite stance:
 * unknown paths throw, so a write of an unknown attribute is rejected at
 * the service boundary rather than silently no-oping. Asymmetry is
 * intentional — the editor must not produce data the resolver cannot
 * subsequently interpret.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun ComponentConfigurationEntity.extractScalarValue(): Any? =
    when (overriddenAttribute) {
        "build.buildSystem" -> buildSystem
        "build.buildSystemVersion" -> buildSystemVersion
        "build.javaVersion" -> javaVersion
        "build.mavenVersion" -> mavenVersion
        "build.gradleVersion" -> gradleVersion
        "build.buildFilePath" -> buildFilePath
        "build.deprecated" -> deprecated
        "build.requiredProject" -> requiredProject
        "build.projectVersion" -> projectVersion
        "build.systemProperties" -> systemProperties
        "build.buildTasks" -> buildTasks
        "escrow.providedDependencies" -> escrowProvidedDependencies
        "escrow.reusable" -> escrowReusable
        "escrow.generation" -> escrowGeneration
        "escrow.diskSpace" -> escrowDiskSpace
        "escrow.additionalSources" -> escrowAdditionalSources
        "escrow.gradleIncludeConfigurations" -> escrowGradleIncludeConfigurations
        "escrow.gradleExcludeConfigurations" -> escrowGradleExcludeConfigurations
        "escrow.gradleIncludeTestConfigurations" -> escrowGradleIncludeTestConfigurations
        "escrow.buildTask" -> escrowBuildTask
        "jira.projectKey" -> jiraProjectKey
        "jira.technical" -> jiraTechnical
        "jira.majorVersionFormat" -> jiraMajorVersionFormat
        "jira.releaseVersionFormat" -> jiraReleaseVersionFormat
        "jira.buildVersionFormat" -> jiraBuildVersionFormat
        "jira.lineVersionFormat" -> jiraLineVersionFormat
        "jira.versionPrefix" -> jiraVersionPrefix
        "jira.versionFormat" -> jiraVersionFormat
        "jira.hotfixVersionFormat" -> jiraHotfixVersionFormat
        "jira.displayName" -> jiraDisplayName
        "vcs.externalRegistry" -> vcsExternalRegistry
        else -> null
    }

/**
 * Set the single typed column corresponding to `attributePath` from a JSON
 * primitive value. Throws [IllegalArgumentException] when the path is unknown
 * or the value is the wrong type — the service maps that to a 400.
 *
 * Mutates the receiver in place; clears every other scalar column first so
 * the row keeps the "exactly one column non-NULL" invariant required by
 * schema-spec.md §7.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
internal fun ComponentConfigurationEntity.applyScalarValue(
    attributePath: String,
    value: Any?,
) {
    require(attributePath in SCALAR_ATTRIBUTE_PATHS) {
        "Unknown scalar attribute path: '$attributePath'"
    }
    require(value != null) {
        "Scalar override value is null — use DELETE /field-overrides/{id} to remove the override"
    }
    // Wipe all typed columns first so we don't leave a stale column non-null
    // from a previous attributePath when this row was a different scalar
    // override (current callers create new rows, but defensive wipe keeps
    // the row a valid SCALAR_OVERRIDE under any reuse pattern).
    clearAllScalarColumns()

    when (attributePath) {
        "build.buildSystem" -> buildSystem = requireBuildSystem(attributePath, value)
        "build.buildSystemVersion" -> buildSystemVersion = requireString(attributePath, value)
        "build.javaVersion" -> javaVersion = requireString(attributePath, value)
        "build.mavenVersion" -> mavenVersion = requireString(attributePath, value)
        "build.gradleVersion" -> gradleVersion = requireString(attributePath, value)
        "build.buildFilePath" -> buildFilePath = requireString(attributePath, value)
        "build.deprecated" -> deprecated = requireBoolean(attributePath, value)
        "build.requiredProject" -> requiredProject = requireBoolean(attributePath, value)
        "build.projectVersion" -> projectVersion = requireString(attributePath, value)
        "build.systemProperties" -> systemProperties = requireString(attributePath, value)
        "build.buildTasks" -> buildTasks = requireString(attributePath, value)
        "escrow.providedDependencies" -> escrowProvidedDependencies = requireString(attributePath, value)
        "escrow.reusable" -> escrowReusable = requireBoolean(attributePath, value)
        "escrow.generation" -> escrowGeneration = requireEscrowGenerationMode(attributePath, value)
        "escrow.diskSpace" -> escrowDiskSpace = requireString(attributePath, value)
        "escrow.additionalSources" -> escrowAdditionalSources = requireString(attributePath, value)
        "escrow.gradleIncludeConfigurations" -> escrowGradleIncludeConfigurations = requireString(attributePath, value)
        "escrow.gradleExcludeConfigurations" -> escrowGradleExcludeConfigurations = requireString(attributePath, value)
        "escrow.gradleIncludeTestConfigurations" -> escrowGradleIncludeTestConfigurations = requireBoolean(attributePath, value)
        "escrow.buildTask" -> escrowBuildTask = requireString(attributePath, value)
        "jira.projectKey" -> jiraProjectKey = requireString(attributePath, value)
        "jira.technical" -> jiraTechnical = requireBoolean(attributePath, value)
        "jira.majorVersionFormat" -> jiraMajorVersionFormat = requireString(attributePath, value)
        "jira.releaseVersionFormat" -> jiraReleaseVersionFormat = requireString(attributePath, value)
        "jira.buildVersionFormat" -> jiraBuildVersionFormat = requireString(attributePath, value)
        "jira.lineVersionFormat" -> jiraLineVersionFormat = requireString(attributePath, value)
        "jira.versionPrefix" -> jiraVersionPrefix = requireString(attributePath, value)
        "jira.versionFormat" -> jiraVersionFormat = requireString(attributePath, value)
        "jira.hotfixVersionFormat" -> jiraHotfixVersionFormat = requireString(attributePath, value)
        "jira.displayName" -> jiraDisplayName = requireString(attributePath, value)
        "vcs.externalRegistry" -> vcsExternalRegistry = requireString(attributePath, value)
    }
}

@Suppress("LongMethod")
private fun ComponentConfigurationEntity.clearAllScalarColumns() {
    buildSystem = null
    buildSystemVersion = null
    javaVersion = null
    mavenVersion = null
    gradleVersion = null
    buildFilePath = null
    deprecated = null
    requiredProject = null
    projectVersion = null
    systemProperties = null
    buildTasks = null
    escrowProvidedDependencies = null
    escrowReusable = null
    escrowGeneration = null
    escrowDiskSpace = null
    escrowAdditionalSources = null
    escrowGradleIncludeConfigurations = null
    escrowGradleExcludeConfigurations = null
    escrowGradleIncludeTestConfigurations = null
    escrowBuildTask = null
    jiraProjectKey = null
    jiraTechnical = null
    jiraMajorVersionFormat = null
    jiraReleaseVersionFormat = null
    jiraBuildVersionFormat = null
    jiraLineVersionFormat = null
    jiraVersionPrefix = null
    jiraVersionFormat = null
    jiraHotfixVersionFormat = null
    jiraDisplayName = null
    vcsExternalRegistry = null
}

private fun requireString(
    path: String,
    value: Any,
): String =
    when (value) {
        is String -> value
        is Number -> value.toString()
        else -> throw IllegalArgumentException("Attribute '$path' expects a string value; got ${value::class.simpleName}")
    }

private fun requireBoolean(
    path: String,
    value: Any,
): Boolean =
    when (value) {
        is Boolean -> value
        is String ->
            value.toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("Attribute '$path' expects boolean; got non-boolean string '$value'")
        else -> throw IllegalArgumentException("Attribute '$path' expects a boolean value; got ${value::class.simpleName}")
    }

/**
 * `build.buildSystem` is special: the resolver parses the stored string with
 * `BuildSystem.valueOf` and silently returns `null` for unknown values. Reject
 * unknown values at the write boundary so the editor surface stays consistent
 * with what the legacy reader can interpret.
 *
 * Both [BUILD_SYSTEM_NAMES] and [ESCROW_GENERATION_MODE_NAMES] are declared in
 * the package-level `EnumValidValues.kt` file — the single source of truth
 * shared with [ComponentManagementServiceImpl][org.octopusden.octopus.components.registry.server.service.impl.ComponentManagementServiceImpl].
 */
private fun requireBuildSystem(
    path: String,
    value: Any,
): String {
    val asString = requireString(path, value)
    require(asString in BUILD_SYSTEM_NAMES) {
        "Attribute '$path' expects one of $BUILD_SYSTEM_NAMES; got '$asString'"
    }
    return asString
}

/**
 * `escrow.generation` is stored as a plain string but the resolver parses it
 * via `EscrowGenerationMode.valueOf`, silently returning `null` for unknown
 * values. Reject at the write boundary so round-trip consistency is guaranteed.
 */
private fun requireEscrowGenerationMode(
    path: String,
    value: Any,
): String {
    val asString = requireString(path, value)
    require(asString in ESCROW_GENERATION_MODE_NAMES) {
        "Attribute '$path' expects one of $ESCROW_GENERATION_MODE_NAMES; got '$asString'"
    }
    return asString
}
