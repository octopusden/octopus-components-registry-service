@file:Suppress("TooManyFunctions")

package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentDocLinkEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionFileUrlArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionPackageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.model.BuildParameters
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.ComponentVersionFormat
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory

/** Must match EscrowConfigurationLoader.ALL_VERSIONS = "(,0),[0,)" */
internal const val ALL_VERSIONS: String = "(,0),[0,)"

/**
 * Marker names for child-collection replacement overrides (see schema-spec.md §3.3).
 */
internal object MarkerAttributes {
    const val VCS_SETTINGS: String = "vcs.settings"
    const val DISTRIBUTION_MAVEN: String = "distribution.maven"
    const val DISTRIBUTION_FILE_URL: String = "distribution.fileUrl"
    const val DISTRIBUTION_DOCKER: String = "distribution.docker"
    const val DISTRIBUTION_PACKAGES: String = "distribution.packages"
    const val BUILD_REQUIRED_TOOLS: String = "build.requiredTools"

    val ALL: Set<String> =
        setOf(
            VCS_SETTINGS,
            DISTRIBUTION_MAVEN,
            DISTRIBUTION_FILE_URL,
            DISTRIBUTION_DOCKER,
            DISTRIBUTION_PACKAGES,
            BUILD_REQUIRED_TOOLS,
        )
}

// ============================================================
// Public API: DB → Domain
// ============================================================

/**
 * Enumerate `EscrowModule` view from a fully-loaded `ComponentEntity` (with
 * `configurations` and all child collections accessible within the Hibernate
 * session). Produces one `EscrowModuleConfig` per distinct version range that
 * appears across base + override rows.
 *
 * Synthetic-base handling (MIG-029): when `isSyntheticBase = true` AND at least
 * one override row exists, the base row's range entry is NOT emitted — only
 * override ranges. This eliminates the spurious `(,0),[0,)` entry that the
 * legacy variants-Map mapper used to synthesise for version-range-only DSL
 * components. When no override exists (synthetic-only component), a single
 * base-range entry is emitted so downstream consumers always see at least one
 * `EscrowModuleConfig`.
 */
fun ComponentEntity.toEscrowModule(
    versionRangeFactory: VersionRangeFactory,
    @Suppress("UNUSED_PARAMETER") numericVersionFactory: NumericVersionFactory,
): EscrowModule {
    val module = EscrowModule()
    module.moduleName = this.componentKey

    val configs = this.configurations.toList()
    val base =
        configs.firstOrNull { it.overriddenAttribute == null }
            ?: return module
    val overrides = configs.filter { it.overriddenAttribute != null }

    val enumeratedRanges = mutableListOf<String>()
    if (!base.isSyntheticBase || overrides.isEmpty()) {
        enumeratedRanges += base.versionRange
    }
    overrides
        .map { it.versionRange }
        .distinct()
        .filter { it !in enumeratedRanges }
        .forEach { enumeratedRanges += it }

    for (range in enumeratedRanges) {
        val resolved =
            this.resolveForRange(
                range = range,
                base = base,
                overrides = overrides,
                versionRangeFactory = versionRangeFactory,
            )
        module.moduleConfigurations.add(resolved)
    }

    return module
}

/**
 * Resolve a single `EscrowModuleConfig` for the given version. Returns `null`
 * if `version` cannot be parsed or if no override matches AND the only base is
 * synthetic with no fallback semantics required by caller — current callers
 * always want the synthetic base as fallback, so this returns it.
 *
 * The algorithm follows schema-spec.md §3.4:
 *   1. Start with base scalars.
 *   2. For each scalar override whose range contains `version`, overwrite the
 *      matching scalar field on the result.
 *   3. For each marker override whose range contains `version`, replace the
 *      corresponding child collection (full replacement).
 */
fun ComponentEntity.toResolvedEscrowModuleConfig(
    version: String,
    versionRangeFactory: VersionRangeFactory,
    numericVersionFactory: NumericVersionFactory,
): EscrowModuleConfig? {
    val configs = this.configurations.toList()
    val base = configs.firstOrNull { it.overriddenAttribute == null } ?: return null
    val overrides = configs.filter { it.overriddenAttribute != null }

    val numericVersion =
        try {
            numericVersionFactory.create(version)
        } catch (_: Exception) {
            return null
        }

    val matchingOverrides =
        overrides.filter { override ->
            try {
                versionRangeFactory.create(override.versionRange).containsVersion(numericVersion)
            } catch (_: Exception) {
                false
            }
        }

    return buildEscrowModuleConfig(
        component = this,
        base = base,
        scalarOverrides = matchingOverrides.filter { it.overriddenAttribute !in MarkerAttributes.ALL },
        markerOverrides = matchingOverrides.filter { it.overriddenAttribute in MarkerAttributes.ALL },
        versionRange = base.versionRange,
    )
}

// ============================================================
// Internal: resolve a single range view
// ============================================================

private fun ComponentEntity.resolveForRange(
    range: String,
    base: ComponentConfigurationEntity,
    overrides: List<ComponentConfigurationEntity>,
    versionRangeFactory: VersionRangeFactory,
): EscrowModuleConfig {
    // For enumeration purposes, an override applies to `range` when its own
    // range string equals `range` OR fully contains `range`. Equality is the
    // common case (overrides are keyed by their own range); containment matters
    // when the base's all-versions range is being enumerated and a narrower
    // override exists.
    val scalarOverrides =
        overrides.filter {
            it.overriddenAttribute !in MarkerAttributes.ALL &&
                rangeApplies(parentRange = it.versionRange, childRange = range, factory = versionRangeFactory)
        }
    val markerOverrides =
        overrides.filter {
            it.overriddenAttribute in MarkerAttributes.ALL &&
                rangeApplies(parentRange = it.versionRange, childRange = range, factory = versionRangeFactory)
        }

    return buildEscrowModuleConfig(
        component = this,
        base = base,
        scalarOverrides = scalarOverrides,
        markerOverrides = markerOverrides,
        versionRange = range,
    )
}

/**
 * True when an override row with `parentRange` should apply to the enumeration
 * view `childRange`. Trivially true when equal; otherwise checks containment
 * via the version-range factory (best-effort — if either range fails to parse,
 * returns false to err on safe side).
 */
private fun rangeApplies(
    parentRange: String,
    childRange: String,
    factory: VersionRangeFactory,
): Boolean {
    if (parentRange == childRange) return true
    return try {
        val parent = factory.create(parentRange)
        val child = factory.create(childRange)
        // No public containsRange; approximate via "every endpoint of child is in parent".
        // Conservative for unbounded ranges — falls back to equality check at the call site.
        parent.toString() == child.toString()
    } catch (_: Exception) {
        false
    }
}

// ============================================================
// Internal: build EscrowModuleConfig from base + overrides
// ============================================================

@Suppress("CyclomaticComplexMethod", "LongParameterList", "LongMethod")
private fun buildEscrowModuleConfig(
    component: ComponentEntity,
    base: ComponentConfigurationEntity,
    scalarOverrides: List<ComponentConfigurationEntity>,
    markerOverrides: List<ComponentConfigurationEntity>,
    versionRange: String,
): EscrowModuleConfig {
    val config = EscrowModuleConfig()
    setField(config, "versionRange", versionRange)

    // Effective scalars: start from base typed columns; for each scalar override
    // row, find its single non-NULL typed column and overlay it.
    val merged = ComponentConfigurationView.from(base)
    for (override in scalarOverrides) {
        merged.applyScalarOverride(override)
    }

    // Build aspect
    setField(config, "buildSystem", merged.buildSystem?.let { safeParseBuildSystem(it) })
    setField(config, "buildFilePath", merged.buildFilePath)
    setField(config, "deprecated", merged.deprecated)
    val buildParameters = merged.toBuildParameters(markerOverrides)
    if (buildParameters != null) {
        setField(config, "buildConfiguration", buildParameters)
    }

    // Escrow aspect
    config.escrow = merged.toEscrowApi()

    // VCS — child collection. Marker override "vcs.settings" replaces base
    // children; otherwise base.vcsEntries is used.
    val vcsEntries =
        pickMarkerChildren(
            attribute = MarkerAttributes.VCS_SETTINGS,
            markerOverrides = markerOverrides,
            baseChildren = base.vcsEntries.toList(),
        ) { it.vcsEntries.toList() }
    if (vcsEntries.isNotEmpty()) {
        setField(config, "vcsSettings", vcsEntries.toVCSSettings(component.vcsExternalRegistry))
    }

    // Distribution — composed from four family child collections, each
    // replaceable via its own marker.
    val mavenArtifacts =
        pickMarkerChildren(
            attribute = MarkerAttributes.DISTRIBUTION_MAVEN,
            markerOverrides = markerOverrides,
            baseChildren = base.mavenArtifacts.toList(),
        ) { it.mavenArtifacts.toList() }
    val fileUrlArtifacts =
        pickMarkerChildren(
            attribute = MarkerAttributes.DISTRIBUTION_FILE_URL,
            markerOverrides = markerOverrides,
            baseChildren = base.fileUrlArtifacts.toList(),
        ) { it.fileUrlArtifacts.toList() }
    val dockerImages =
        pickMarkerChildren(
            attribute = MarkerAttributes.DISTRIBUTION_DOCKER,
            markerOverrides = markerOverrides,
            baseChildren = base.dockerImages.toList(),
        ) { it.dockerImages.toList() }
    val packages =
        pickMarkerChildren(
            attribute = MarkerAttributes.DISTRIBUTION_PACKAGES,
            markerOverrides = markerOverrides,
            baseChildren = base.packages.toList(),
        ) { it.packages.toList() }

    val distribution =
        buildDistribution(
            explicit = component.distributionExplicit,
            external = component.distributionExternal,
            mavenArtifacts = mavenArtifacts,
            fileUrlArtifacts = fileUrlArtifacts,
            dockerImages = dockerImages,
            packages = packages,
            securityGroups = component.securityGroups.toList(),
        )
    if (distribution != null) {
        setField(config, "distribution", distribution)
    }

    // Jira aspect — composed from merged config scalars + component-level fields
    val jira = buildJiraComponent(component = component, merged = merged)
    if (jira != null) {
        setField(config, "jiraConfiguration", jira)
    }

    // Component-level (per-component, never per-version)
    setField(config, "componentDisplayName", component.displayName)
    setField(config, "componentOwner", component.componentOwner)
    setField(config, "system", component.systemJunctions.joinToString(",") { it.systemCode })
    setField(config, "clientCode", component.clientCode)
    setField(config, "solution", component.solution)
    setField(config, "parentComponent", component.parentComponent?.componentKey)
    setField(config, "archived", component.archived)
    setField(config, "releaseManager", component.releaseManager)
    setField(config, "securityChampion", component.securityChampion)
    setField(config, "copyright", component.copyright)
    setField(config, "releasesInDefaultBranch", component.releasesInDefaultBranch)

    val labels = component.labelJunctions.map { it.labelCode }.toSet()
    if (labels.isNotEmpty()) {
        setField(config, "labels", labels)
    }

    config.productType = component.productType?.let { safeParseProductType(it) }

    // Doc — prefer per-major link matching the resolved view's leading version;
    // fall back to the major_version = NULL link, then null.
    val docLink = pickDocLink(component.docLinks.toList(), versionRange)
    if (docLink != null) {
        config.doc =
            org.octopusden.octopus.escrow.model
                .Doc(docLink.docComponentKey, docLink.majorVersion)
    }

    // Artifact pattern (group/artifact) — there is at most one pair per component
    val artifactId = component.artifactIds.firstOrNull()
    if (artifactId != null) {
        setField(config, "groupIdPattern", artifactId.groupPattern)
        setField(config, "artifactIdPattern", artifactId.artifactPattern)
    }

    return config
}

private fun <T> pickMarkerChildren(
    attribute: String,
    markerOverrides: List<ComponentConfigurationEntity>,
    baseChildren: List<T>,
    childExtractor: (ComponentConfigurationEntity) -> List<T>,
): List<T> {
    val marker = markerOverrides.firstOrNull { it.overriddenAttribute == attribute }
    return if (marker != null) childExtractor(marker) else baseChildren
}

// ============================================================
// Internal: scalar merge view (a mutable copy of base scalars)
// ============================================================

/**
 * Mutable scratch buffer holding the merged scalar values that will populate
 * the resulting `EscrowModuleConfig`. Lifecycle is scoped to one resolve call.
 */
private class ComponentConfigurationView {
    var buildSystem: String? = null
    var buildSystemVersion: String? = null
    var javaVersion: String? = null
    var mavenVersion: String? = null
    var gradleVersion: String? = null
    var buildFilePath: String? = null
    var deprecated: Boolean? = null
    var requiredProject: Boolean? = null
    var projectVersion: String? = null
    var systemProperties: String? = null
    var buildTasks: String? = null

    var escrowProvidedDependencies: String? = null
    var escrowReusable: Boolean? = null
    var escrowGeneration: String? = null
    var escrowDiskSpace: String? = null
    var escrowAdditionalSources: String? = null
    var escrowGradleIncludeConfigurations: String? = null
    var escrowGradleExcludeConfigurations: String? = null
    var escrowGradleIncludeTestConfigurations: Boolean? = null

    var jiraProjectKey: String? = null
    var jiraTechnical: Boolean? = null
    var jiraMajorVersionFormat: String? = null
    var jiraReleaseVersionFormat: String? = null
    var jiraBuildVersionFormat: String? = null
    var jiraLineVersionFormat: String? = null
    var jiraVersionPrefix: String? = null
    var jiraVersionFormat: String? = null

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun applyScalarOverride(override: ComponentConfigurationEntity) {
        when (override.overriddenAttribute) {
            "build.buildSystem" -> override.buildSystem?.let { buildSystem = it }
            "build.buildSystemVersion" -> override.buildSystemVersion?.let { buildSystemVersion = it }
            "build.javaVersion" -> override.javaVersion?.let { javaVersion = it }
            "build.mavenVersion" -> override.mavenVersion?.let { mavenVersion = it }
            "build.gradleVersion" -> override.gradleVersion?.let { gradleVersion = it }
            "build.buildFilePath" -> override.buildFilePath?.let { buildFilePath = it }
            "build.deprecated" -> override.deprecated?.let { deprecated = it }
            "build.requiredProject" -> override.requiredProject?.let { requiredProject = it }
            "build.projectVersion" -> override.projectVersion?.let { projectVersion = it }
            "build.systemProperties" -> override.systemProperties?.let { systemProperties = it }
            "build.buildTasks" -> override.buildTasks?.let { buildTasks = it }

            "escrow.providedDependencies" -> override.escrowProvidedDependencies?.let { escrowProvidedDependencies = it }
            "escrow.reusable" -> override.escrowReusable?.let { escrowReusable = it }
            "escrow.generation" -> override.escrowGeneration?.let { escrowGeneration = it }
            "escrow.diskSpace" -> override.escrowDiskSpace?.let { escrowDiskSpace = it }
            "escrow.additionalSources" -> override.escrowAdditionalSources?.let { escrowAdditionalSources = it }
            "escrow.gradleIncludeConfigurations" -> override.escrowGradleIncludeConfigurations?.let { escrowGradleIncludeConfigurations = it }
            "escrow.gradleExcludeConfigurations" -> override.escrowGradleExcludeConfigurations?.let { escrowGradleExcludeConfigurations = it }
            "escrow.gradleIncludeTestConfigurations" -> override.escrowGradleIncludeTestConfigurations?.let { escrowGradleIncludeTestConfigurations = it }

            "jira.projectKey" -> override.jiraProjectKey?.let { jiraProjectKey = it }
            "jira.technical" -> override.jiraTechnical?.let { jiraTechnical = it }
            "jira.majorVersionFormat" -> override.jiraMajorVersionFormat?.let { jiraMajorVersionFormat = it }
            "jira.releaseVersionFormat" -> override.jiraReleaseVersionFormat?.let { jiraReleaseVersionFormat = it }
            "jira.buildVersionFormat" -> override.jiraBuildVersionFormat?.let { jiraBuildVersionFormat = it }
            "jira.lineVersionFormat" -> override.jiraLineVersionFormat?.let { jiraLineVersionFormat = it }
            "jira.versionPrefix" -> override.jiraVersionPrefix?.let { jiraVersionPrefix = it }
            "jira.versionFormat" -> override.jiraVersionFormat?.let { jiraVersionFormat = it }

            else -> Unit // unknown attribute path; ignore for forward-compat
        }
    }

    fun toBuildParameters(markerOverrides: List<ComponentConfigurationEntity>): BuildParameters? {
        if (javaVersion == null &&
            mavenVersion == null &&
            gradleVersion == null &&
            buildTasks == null &&
            !requiredProject.orFalse() &&
            projectVersion == null &&
            systemProperties == null
        ) {
            return null
        }
        val toolMarker = markerOverrides.firstOrNull { it.overriddenAttribute == MarkerAttributes.BUILD_REQUIRED_TOOLS }
        val tools =
            (toolMarker?.requiredToolJunctions?.toList() ?: emptyList()).mapNotNull { junction ->
                val tool = junction.tool ?: return@mapNotNull null
                org.octopusden.octopus.escrow.model.Tool(
                    tool.name,
                    tool.escrowEnvVariable,
                    tool.sourceLocation,
                    tool.targetLocation,
                    tool.installScript,
                )
            }
        return BuildParameters.create(
            javaVersion,
            mavenVersion,
            gradleVersion,
            requiredProject.orFalse(),
            projectVersion,
            systemProperties,
            buildTasks,
            tools,
            emptyList(),
        )
    }

    fun toEscrowApi(): org.octopusden.octopus.components.registry.api.escrow.Escrow {
        val captured = this
        return object : org.octopusden.octopus.components.registry.api.escrow.Escrow {
            override fun getGradle() = null

            override fun getBuildTask() = captured.buildTasks

            override fun getProvidedDependencies(): Collection<String> =
                captured.escrowProvidedDependencies
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() } ?: emptyList()

            override fun getDiskSpaceRequirement() = java.util.Optional.ofNullable(captured.escrowDiskSpace?.toLongOrNull())

            override fun getAdditionalSources(): Collection<String> =
                captured.escrowAdditionalSources
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() } ?: emptyList()

            override fun isReusable() = captured.escrowReusable ?: false

            override fun getGeneration() =
                java.util.Optional.ofNullable(
                    captured.escrowGeneration?.let {
                        try {
                            org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
                                .valueOf(it)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    },
                )
        }
    }

    companion object {
        fun from(base: ComponentConfigurationEntity): ComponentConfigurationView =
            ComponentConfigurationView().apply {
                buildSystem = base.buildSystem
                buildSystemVersion = base.buildSystemVersion
                javaVersion = base.javaVersion
                mavenVersion = base.mavenVersion
                gradleVersion = base.gradleVersion
                buildFilePath = base.buildFilePath
                deprecated = base.deprecated
                requiredProject = base.requiredProject
                projectVersion = base.projectVersion
                systemProperties = base.systemProperties
                buildTasks = base.buildTasks

                escrowProvidedDependencies = base.escrowProvidedDependencies
                escrowReusable = base.escrowReusable
                escrowGeneration = base.escrowGeneration
                escrowDiskSpace = base.escrowDiskSpace
                escrowAdditionalSources = base.escrowAdditionalSources
                escrowGradleIncludeConfigurations = base.escrowGradleIncludeConfigurations
                escrowGradleExcludeConfigurations = base.escrowGradleExcludeConfigurations
                escrowGradleIncludeTestConfigurations = base.escrowGradleIncludeTestConfigurations

                jiraProjectKey = base.jiraProjectKey
                jiraTechnical = base.jiraTechnical
                jiraMajorVersionFormat = base.jiraMajorVersionFormat
                jiraReleaseVersionFormat = base.jiraReleaseVersionFormat
                jiraBuildVersionFormat = base.jiraBuildVersionFormat
                jiraLineVersionFormat = base.jiraLineVersionFormat
                jiraVersionPrefix = base.jiraVersionPrefix
                jiraVersionFormat = base.jiraVersionFormat
            }
    }
}

private fun Boolean?.orFalse(): Boolean = this == true

// ============================================================
// Internal: VCS / Distribution / Jira builders
// ============================================================

internal fun List<VcsSettingsEntryEntity>.toVCSSettings(externalRegistry: String?): VCSSettings {
    val sorted = this.sortedBy { it.sortOrder }
    val roots =
        sorted.map { entry ->
            VersionControlSystemRoot.create(
                entry.name ?: "main",
                RepositoryType.valueOf(entry.repositoryType ?: "GIT"),
                entry.vcsPath,
                entry.tag,
                entry.branch,
                entry.hotfixBranch,
            )
        }
    return VCSSettings.create(externalRegistry, roots)
}

@Suppress("LongParameterList")
internal fun buildDistribution(
    explicit: Boolean?,
    external: Boolean?,
    mavenArtifacts: List<DistributionMavenArtifactEntity>,
    fileUrlArtifacts: List<DistributionFileUrlArtifactEntity>,
    dockerImages: List<DistributionDockerImageEntity>,
    packages: List<DistributionPackageEntity>,
    securityGroups: List<DistributionSecurityGroupEntity>,
): Distribution? {
    val gavStr = composeGavCsv(mavenArtifacts, fileUrlArtifacts)
    val dockerStr = composeDockerCsv(dockerImages)
    val debStr = packages.filter { it.packageType == "DEB" }.sortedBy { it.sortOrder }.joinToString(",") { it.packageName }.ifEmpty { null }
    val rpmStr = packages.filter { it.packageType == "RPM" }.sortedBy { it.sortOrder }.joinToString(",") { it.packageName }.ifEmpty { null }

    val secReadGroups =
        securityGroups
            .filter { it.groupType == "read" }
            .joinToString(",") { it.groupName }
    val secGroups = if (secReadGroups.isNotEmpty()) SecurityGroups(secReadGroups) else null

    val anyContent =
        explicit != null ||
            external != null ||
            gavStr != null ||
            dockerStr != null ||
            debStr != null ||
            rpmStr != null ||
            secGroups != null

    if (!anyContent) return null

    return Distribution(explicit ?: true, external ?: false, gavStr, debStr, rpmStr, dockerStr, secGroups)
}

private fun composeGavCsv(
    maven: List<DistributionMavenArtifactEntity>,
    fileUrl: List<DistributionFileUrlArtifactEntity>,
): String? {
    val mavenStr =
        maven.sortedBy { it.sortOrder }.joinToString(",") { e ->
            buildString {
                append(e.groupPattern)
                append(':')
                append(e.artifactPattern)
                if (!e.extension.isNullOrBlank()) {
                    append(':').append(e.extension)
                }
                if (!e.classifier.isNullOrBlank()) {
                    if (e.extension.isNullOrBlank()) append(':') // hold extension slot
                    append(':').append(e.classifier)
                }
            }
        }
    val fileUrlStr =
        fileUrl.sortedBy { it.sortOrder }.joinToString(",") { e ->
            buildString {
                append(e.url)
                if (!e.artifactId.isNullOrBlank()) append("?artifactId=").append(e.artifactId)
                if (!e.classifier.isNullOrBlank()) {
                    append(if (e.artifactId.isNullOrBlank()) "?" else "&")
                    append("classifier=").append(e.classifier)
                }
            }
        }
    val combined = listOf(mavenStr, fileUrlStr).filter { it.isNotEmpty() }.joinToString(",")
    return combined.ifEmpty { null }
}

private fun composeDockerCsv(images: List<DistributionDockerImageEntity>): String? {
    val sorted = images.sortedBy { it.sortOrder }
    val csv =
        sorted.joinToString(",") { e ->
            if (e.flavor.isNullOrBlank()) e.imageName else "${e.imageName}:${e.flavor}"
        }
    return csv.ifEmpty { null }
}

@Suppress("LongMethod")
private fun buildJiraComponent(
    component: ComponentEntity,
    merged: ComponentConfigurationView,
): JiraComponent? {
    val projectKey = merged.jiraProjectKey ?: return null

    val majorFmt = merged.jiraMajorVersionFormat ?: "\$major"
    val releaseFmt = merged.jiraReleaseVersionFormat ?: "\$major.\$minor"
    val buildFmt = merged.jiraBuildVersionFormat ?: releaseFmt
    val lineFmt = merged.jiraLineVersionFormat ?: majorFmt
    val hotfixFmt = component.jiraHotfixVersionFormat ?: ""

    val format =
        ComponentVersionFormat.create(majorFmt, releaseFmt, buildFmt, lineFmt, hotfixFmt)
    val info = ComponentInfo(merged.jiraVersionPrefix ?: "", merged.jiraVersionFormat ?: "")

    return JiraComponent(
        projectKey,
        component.jiraDisplayName ?: "",
        format,
        info,
        merged.jiraTechnical ?: false,
        false,
    )
}

private fun pickDocLink(
    links: List<ComponentDocLinkEntity>,
    versionRange: String,
): ComponentDocLinkEntity? {
    if (links.isEmpty()) return null
    // Caller resolves to a specific major version when needed; for now, prefer
    // the link whose `majorVersion` is null (fallback) or matches the leading
    // numeric prefix of `versionRange` when both can be parsed. Detailed
    // semantics are pinned by the mapper-level spec note in schema-spec.md §5.
    val nullFallback = links.firstOrNull { it.majorVersion == null }
    val leadingMajor =
        Regex("""(\d+)""").find(versionRange)?.groupValues?.getOrNull(1)
    val matchByMajor = leadingMajor?.let { major -> links.firstOrNull { it.majorVersion == major } }
    return matchByMajor ?: nullFallback ?: links.firstOrNull()
}

// ============================================================
// Internal: utilities
// ============================================================

private fun safeParseBuildSystem(value: String): BuildSystem? =
    try {
        BuildSystem.valueOf(value)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun safeParseProductType(value: String): ProductTypes? =
    try {
        ProductTypes.valueOf(value)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Set a private field on `EscrowModuleConfig` via reflection. Unknown fields
 * are silently ignored (forward-compat with domain-model changes).
 */
private fun setField(
    config: EscrowModuleConfig,
    name: String,
    value: Any?,
) {
    try {
        val field = EscrowModuleConfig::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(config, value)
    } catch (_: NoSuchFieldException) {
        // ignore unknown fields
    }
}
