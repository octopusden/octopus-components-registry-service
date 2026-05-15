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
        "jira.projectKey",
        "jira.technical",
        "jira.majorVersionFormat",
        "jira.releaseVersionFormat",
        "jira.buildVersionFormat",
        "jira.lineVersionFormat",
        "jira.versionPrefix",
        "jira.versionFormat",
    )

/**
 * Read the single typed column corresponding to `overriddenAttribute` on a
 * SCALAR_OVERRIDE row. Returns null if the path is unknown or the column is
 * NULL.
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
        "jira.projectKey" -> jiraProjectKey
        "jira.technical" -> jiraTechnical
        "jira.majorVersionFormat" -> jiraMajorVersionFormat
        "jira.releaseVersionFormat" -> jiraReleaseVersionFormat
        "jira.buildVersionFormat" -> jiraBuildVersionFormat
        "jira.lineVersionFormat" -> jiraLineVersionFormat
        "jira.versionPrefix" -> jiraVersionPrefix
        "jira.versionFormat" -> jiraVersionFormat
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
        "build.buildSystem" -> buildSystem = requireString(attributePath, value)
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
        "escrow.generation" -> escrowGeneration = requireString(attributePath, value)
        "escrow.diskSpace" -> escrowDiskSpace = requireString(attributePath, value)
        "escrow.additionalSources" -> escrowAdditionalSources = requireString(attributePath, value)
        "escrow.gradleIncludeConfigurations" -> escrowGradleIncludeConfigurations = requireString(attributePath, value)
        "escrow.gradleExcludeConfigurations" -> escrowGradleExcludeConfigurations = requireString(attributePath, value)
        "escrow.gradleIncludeTestConfigurations" -> escrowGradleIncludeTestConfigurations = requireBoolean(attributePath, value)
        "jira.projectKey" -> jiraProjectKey = requireString(attributePath, value)
        "jira.technical" -> jiraTechnical = requireBoolean(attributePath, value)
        "jira.majorVersionFormat" -> jiraMajorVersionFormat = requireString(attributePath, value)
        "jira.releaseVersionFormat" -> jiraReleaseVersionFormat = requireString(attributePath, value)
        "jira.buildVersionFormat" -> jiraBuildVersionFormat = requireString(attributePath, value)
        "jira.lineVersionFormat" -> jiraLineVersionFormat = requireString(attributePath, value)
        "jira.versionPrefix" -> jiraVersionPrefix = requireString(attributePath, value)
        "jira.versionFormat" -> jiraVersionFormat = requireString(attributePath, value)
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
    jiraProjectKey = null
    jiraTechnical = null
    jiraMajorVersionFormat = null
    jiraReleaseVersionFormat = null
    jiraBuildVersionFormat = null
    jiraLineVersionFormat = null
    jiraVersionPrefix = null
    jiraVersionFormat = null
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
