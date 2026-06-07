package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionFileUrlArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionPackageEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.mapper.ComponentConfigurationView
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.mapper.extractScalarValue
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Renders a [ComponentEntity] as a human-readable, Groovy-style "as-code"
 * definition — the reverse of the legacy Groovy import (which no longer exists
 * in the code). Pure [StringBuilder]; **no GroovyShell / old escrow libraries**.
 *
 * Two modes:
 *
 *  - [renderFull] — the whole component WITH all version ranges: a top-level
 *    block built from the BASE row + per-component fields, followed by one
 *    `"<range>" { … }` block per distinct override range (delta-style — only the
 *    fields that the range overrides). Mirrors how the data is stored and how
 *    the DSL was authored.
 *
 *  - [renderResolved] — the component flattened/merged for ONE concrete version:
 *    a single block, no range sub-blocks. The merge reuses the exact same
 *    primitives the resolver uses ([ComponentConfigurationView.applyScalarOverride]
 *    for scalars, marker-pick for child collections), so the values match what
 *    the v2 version-resolution endpoints return — with one deliberate exception:
 *    distribution `$version` substitution (a runtime concern) is NOT applied, so
 *    the resolved view shows the same distribution patterns as the full view.
 *
 * Visual format (old Groovy look):
 * ```
 * bcomponent {
 *     componentOwner = "user1"
 *     vcsSettings {
 *         vcsUrl = "ssh://git@github/bcomponent.git"
 *         repositoryType = GIT
 *     }
 *     build {
 *         buildSystem = MAVEN
 *         javaVersion = "1.8"
 *     }
 *     jira {
 *         projectKey = "BS"
 *         majorVersionFormat = '$major'
 *     }
 *     "[1.5,)" {
 *         jira {
 *             releaseVersionFormat = '$major.$minor'
 *         }
 *     }
 * }
 * ```
 *
 * Enum-valued fields (`buildSystem`, `repositoryType`, `generation`) and booleans
 * render as bare words; strings are quoted; strings containing `$` use single
 * quotes so the literal `$placeholder` is preserved (matching the old format).
 */
@Component
@Suppress("TooManyFunctions")
class ComponentCodeRenderer(
    private val versionRangeFactory: VersionRangeFactory,
    private val numericVersionFactory: NumericVersionFactory,
) {
    /** FULL view: base block + one `"<range>" { … }` block per distinct override range. */
    fun renderFull(component: ComponentEntity): String {
        val configs =
            component.configurations.sortedWith(
                compareBy(
                    { it.rowType != ROW_BASE },
                    { it.createdAt ?: Instant.MIN },
                    { it.id?.toString() ?: "" },
                ),
            )
        val base = configs.firstOrNull { it.rowType == ROW_BASE }
        val overrides = configs.filter { it.rowType != ROW_BASE }

        val cb = CodeBuilder()
        cb.open(blockHeader(component.componentKey))
        if (base == null) {
            cb.close()
            return cb.toString()
        }

        // Always render the base row's aspects. Even a "synthetic" base (one with
        // no explicit all-versions DSL block) carries the real shared values the
        // Groovy loader merged in from the component's top-level fields, and the
        // resolver starts from the base row for EVERY version — so these are the
        // fallback that applies to all version ranges. Suppressing them made
        // base-level vcsSettings / build / jira / distribution look range-only.
        // (The earlier MIG-029 suppression was about not emitting a spurious
        // all-versions *variant* in the v1-v3 map — a different concern that does
        // not apply to this code view.)
        writeComponentBody(
            cb = cb,
            component = component,
            scalars = ComponentConfigurationView.from(base),
            vcsEntries = base.vcsEntries.toList(),
            mavenArtifacts = base.mavenArtifacts.toList(),
            fileUrlArtifacts = base.fileUrlArtifacts.toList(),
            dockerImages = base.dockerImages.toList(),
            packages = base.packages.toList(),
            requiredTools = base.requiredToolJunctions.map { it.toolName },
            buildToolBeans = base.buildToolBeans.toList(),
        )

        // Distinct override ranges in deterministic (DSL declaration) order.
        overrides
            .map { it.versionRange }
            .distinct()
            .forEach { range -> writeRangeBlock(cb, range, overrides.filter { it.versionRange == range }) }

        cb.close()
        return cb.toString()
    }

    /**
     * RESOLVED view for [version]. Returns `null` when the component has no BASE
     * row or [version] is unparseable (the caller maps that to a 404), matching
     * `EntityMappers.toResolvedEscrowModuleConfig`.
     */
    @Suppress("ReturnCount")
    fun renderResolved(
        component: ComponentEntity,
        version: String,
    ): String? {
        val configs = component.configurations.toList()
        val base = configs.firstOrNull { it.rowType == ROW_BASE } ?: return null
        val numericVersion =
            try {
                numericVersionFactory.create(version)
            } catch (_: Exception) {
                return null
            }
        val matching =
            configs.filter { it.rowType == ROW_SCALAR_OVERRIDE || it.rowType == ROW_MARKER }
                .filter { override ->
                    try {
                        versionRangeFactory.create(override.versionRange).containsVersion(numericVersion)
                    } catch (_: Exception) {
                        false
                    }
                }
        val scalarOverrides = matching.filter { it.rowType == ROW_SCALAR_OVERRIDE }
        val markerOverrides = matching.filter { it.rowType == ROW_MARKER }

        val view = ComponentConfigurationView.from(base)
        scalarOverrides.forEach { view.applyScalarOverride(it) }

        val vcs =
            pickMarkerChildren(MarkerAttributes.VCS_SETTINGS, markerOverrides, base.vcsEntries.toList()) { it.vcsEntries.toList() }
        val maven =
            pickMarkerChildren(MarkerAttributes.DISTRIBUTION_MAVEN, markerOverrides, base.mavenArtifacts.toList()) {
                it.mavenArtifacts.toList()
            }
        val fileUrl =
            pickMarkerChildren(MarkerAttributes.DISTRIBUTION_FILE_URL, markerOverrides, base.fileUrlArtifacts.toList()) {
                it.fileUrlArtifacts.toList()
            }
        val docker =
            pickMarkerChildren(MarkerAttributes.DISTRIBUTION_DOCKER, markerOverrides, base.dockerImages.toList()) {
                it.dockerImages.toList()
            }
        val pkgs =
            pickMarkerChildren(MarkerAttributes.DISTRIBUTION_PACKAGES, markerOverrides, base.packages.toList()) { it.packages.toList() }
        val tools =
            pickMarkerChildren(MarkerAttributes.BUILD_REQUIRED_TOOLS, markerOverrides, base.requiredToolJunctions.toList()) {
                it.requiredToolJunctions.toList()
            }.map { it.toolName }
        val beans =
            pickMarkerChildren(MarkerAttributes.BUILD_TOOLS, markerOverrides, base.buildToolBeans.toList()) { it.buildToolBeans.toList() }

        val cb = CodeBuilder()
        cb.open(blockHeader(component.componentKey))
        writeComponentBody(
            cb = cb,
            component = component,
            scalars = view,
            vcsEntries = vcs,
            mavenArtifacts = maven,
            fileUrlArtifacts = fileUrl,
            dockerImages = docker,
            packages = pkgs,
            requiredTools = tools,
            buildToolBeans = beans,
        )
        cb.close()
        return cb.toString()
    }

    // ============================================================
    // Component body (shared by full base block and resolved block)
    // ============================================================

    @Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
    private fun writeComponentBody(
        cb: CodeBuilder,
        component: ComponentEntity,
        scalars: ComponentConfigurationView,
        vcsEntries: List<VcsSettingsEntryEntity>,
        mavenArtifacts: List<DistributionMavenArtifactEntity>,
        fileUrlArtifacts: List<DistributionFileUrlArtifactEntity>,
        dockerImages: List<DistributionDockerImageEntity>,
        packages: List<DistributionPackageEntity>,
        requiredTools: List<String>,
        buildToolBeans: List<ComponentBuildToolBeanEntity>,
    ) {
        // Identity
        cb.str("displayName", component.displayName)
        cb.str("componentOwner", component.componentOwner)
        cb.str("system", component.systemCode)
        cb.str("productType", component.productType)
        cb.str("clientCode", component.clientCode)
        cb.bool("solution", component.solution)
        cb.bool("archived", component.archived.takeIf { it })
        cb.bool("canBeParent", component.canBeParent.takeIf { it })
        cb.bool("releasesInDefaultBranch", component.releasesInDefaultBranch)
        cb.str("copyright", component.copyright)
        cb.str("parentComponent", component.parentComponent?.componentKey)
        cb.strList("releaseManager", component.releaseManagerUsernames())
        cb.strList("securityChampion", component.securityChampionUsernames())
        cb.strList("labels", component.labelJunctions.map { it.labelCode })

        writeVcs(cb, vcsEntries, component.vcsExternalRegistry)
        writeBuild(cb, scalars, requiredTools, buildToolBeans)
        writeArtifactIds(cb, component)
        writeJira(cb, scalars, component)
        writeDistribution(cb, component, mavenArtifacts, fileUrlArtifacts, dockerImages, packages)
        writeEscrow(cb, scalars)
        writeDocs(cb, component)
        cb.strList("teamcityProjects", component.teamcityProjects.sortedBy { it.sortOrder }.map { it.projectId })
    }

    private fun writeVcs(
        cb: CodeBuilder,
        entries: List<VcsSettingsEntryEntity>,
        externalRegistry: String?,
    ) {
        if (entries.isEmpty() && externalRegistry == null) return
        cb.open("vcsSettings")
        cb.str("externalRegistry", externalRegistry)
        val sorted = entries.sortedBy { it.sortOrder }
        if (sorted.size <= 1) {
            sorted.firstOrNull()?.let { writeVcsRootFields(cb, it) }
        } else {
            // Multiple named roots → one named sub-block each (name is always quoted).
            sorted.forEach { root ->
                cb.open(doubleQuoted(root.name))
                writeVcsRootFields(cb, root)
                cb.close()
            }
        }
        cb.close()
    }

    private fun writeVcsRootFields(
        cb: CodeBuilder,
        root: VcsSettingsEntryEntity,
    ) {
        cb.str("vcsUrl", root.vcsPath)
        cb.bare("repositoryType", root.repositoryType)
        cb.str("branch", root.branch)
        cb.str("tag", root.tag)
        cb.str("hotfixBranch", root.hotfixBranch)
    }

    private fun writeBuild(
        cb: CodeBuilder,
        s: ComponentConfigurationView,
        requiredTools: List<String>,
        buildToolBeans: List<ComponentBuildToolBeanEntity>,
    ) {
        val hasContent =
            listOf(
                s.buildSystem, s.javaVersion, s.mavenVersion, s.gradleVersion,
                s.buildFilePath, s.projectVersion, s.systemProperties, s.buildTasks,
            ).any { it != null } || s.deprecated != null || s.requiredProject != null ||
                requiredTools.isNotEmpty() || buildToolBeans.isNotEmpty()
        if (!hasContent) return
        cb.open("build")
        cb.bare("buildSystem", s.buildSystem)
        cb.str("javaVersion", s.javaVersion)
        cb.str("mavenVersion", s.mavenVersion)
        cb.str("gradleVersion", s.gradleVersion)
        cb.str("buildFilePath", s.buildFilePath)
        cb.bool("deprecated", s.deprecated)
        cb.bool("requiredProject", s.requiredProject)
        cb.str("projectVersion", s.projectVersion)
        cb.str("systemProperties", s.systemProperties)
        cb.str("buildTasks", s.buildTasks)
        cb.strList("requiredTools", requiredTools)
        writeBuildToolBeans(cb, buildToolBeans)
        cb.close()
    }

    private fun writeBuildToolBeans(
        cb: CodeBuilder,
        beans: List<ComponentBuildToolBeanEntity>,
    ) {
        if (beans.isEmpty()) return
        cb.open("buildTools")
        beans.sortedBy { it.sortOrder }.forEach { bean ->
            cb.open("tool")
            cb.str("beanType", bean.beanType)
            cb.str("toolType", bean.toolType)
            cb.str("settingsProperty", bean.settingsProperty)
            cb.str("versionPattern", bean.versionPattern)
            cb.str("edition", bean.edition)
            cb.close()
        }
        cb.close()
    }

    private fun writeArtifactIds(
        cb: CodeBuilder,
        component: ComponentEntity,
    ) {
        val artifactIds = component.artifactIds
        if (artifactIds.isEmpty()) return
        cb.open("artifactIds")
        artifactIds.forEach { a ->
            cb.open("artifact")
            cb.str("groupPattern", a.groupPattern)
            cb.str("artifactPattern", a.artifactPattern)
            cb.close()
        }
        cb.close()
    }

    private fun writeJira(
        cb: CodeBuilder,
        s: ComponentConfigurationView,
        component: ComponentEntity,
    ) {
        // hotfixVersionFormat has a per-component fallback layer. Mirror the resolver
        // (`EntityMappers.buildJiraComponent`): once a per-range SCALAR_OVERRIDE has set it
        // (`jiraHotfixVersionFormatOverridden`), the merged value wins as-is — including a
        // null-clear; otherwise fall back to the per-component base value.
        val effectiveHotfix =
            if (s.jiraHotfixVersionFormatOverridden) {
                s.jiraHotfixVersionFormat
            } else {
                s.jiraHotfixVersionFormat ?: component.jiraHotfixVersionFormat
            }
        val hasContent =
            listOf(
                s.jiraProjectKey, s.jiraMajorVersionFormat, s.jiraReleaseVersionFormat, s.jiraBuildVersionFormat,
                s.jiraLineVersionFormat, s.jiraVersionPrefix, s.jiraVersionFormat, effectiveHotfix,
                component.jiraDisplayName,
            ).any { it != null } || s.jiraTechnical != null
        if (!hasContent) return
        cb.open("jira")
        cb.str("projectKey", s.jiraProjectKey)
        cb.str("displayName", component.jiraDisplayName)
        cb.bool("technical", s.jiraTechnical)
        cb.str("majorVersionFormat", s.jiraMajorVersionFormat)
        cb.str("releaseVersionFormat", s.jiraReleaseVersionFormat)
        cb.str("buildVersionFormat", s.jiraBuildVersionFormat)
        cb.str("lineVersionFormat", s.jiraLineVersionFormat)
        cb.str("versionPrefix", s.jiraVersionPrefix)
        cb.str("versionFormat", s.jiraVersionFormat)
        cb.str("hotfixVersionFormat", effectiveHotfix)
        cb.close()
    }

    @Suppress("LongParameterList")
    private fun writeDistribution(
        cb: CodeBuilder,
        component: ComponentEntity,
        mavenArtifacts: List<DistributionMavenArtifactEntity>,
        fileUrlArtifacts: List<DistributionFileUrlArtifactEntity>,
        dockerImages: List<DistributionDockerImageEntity>,
        packages: List<DistributionPackageEntity>,
    ) {
        val securityGroups = component.securityGroups
        val hasContent =
            component.distributionExplicit != null || component.distributionExternal != null ||
                mavenArtifacts.isNotEmpty() || fileUrlArtifacts.isNotEmpty() || dockerImages.isNotEmpty() ||
                packages.isNotEmpty() || securityGroups.isNotEmpty()
        if (!hasContent) return
        cb.open("distribution")
        cb.bool("explicit", component.distributionExplicit)
        cb.bool("external", component.distributionExternal)
        mavenArtifacts.sortedBy { it.sortOrder }.forEach { m ->
            cb.open("maven")
            cb.str("groupPattern", m.groupPattern)
            cb.str("artifactPattern", m.artifactPattern)
            cb.str("extension", m.extension)
            cb.str("classifier", m.classifier)
            cb.close()
        }
        fileUrlArtifacts.sortedBy { it.sortOrder }.forEach { f ->
            cb.open("fileUrl")
            cb.str("url", f.url)
            cb.str("artifactId", f.artifactId)
            cb.str("classifier", f.classifier)
            cb.close()
        }
        dockerImages.sortedBy { it.sortOrder }.forEach { d ->
            cb.open("docker")
            cb.str("imageName", d.imageName)
            cb.str("flavor", d.flavor)
            cb.close()
        }
        packages.sortedBy { it.sortOrder }.forEach { p ->
            cb.open("package")
            cb.bare("type", p.packageType)
            cb.str("name", p.packageName)
            cb.close()
        }
        if (securityGroups.isNotEmpty()) {
            cb.open("securityGroups")
            securityGroups.forEach { g -> cb.str(g.groupType, g.groupName) }
            cb.close()
        }
        cb.close()
    }

    private fun writeEscrow(
        cb: CodeBuilder,
        s: ComponentConfigurationView,
    ) {
        val hasContent =
            listOf(
                s.escrowBuildTask, s.escrowProvidedDependencies, s.escrowGeneration, s.escrowDiskSpace,
                s.escrowAdditionalSources, s.escrowGradleIncludeConfigurations, s.escrowGradleExcludeConfigurations,
            ).any { it != null } || s.escrowReusable != null || s.escrowGradleIncludeTestConfigurations != null
        if (!hasContent) return
        cb.open("escrow")
        cb.str("buildTask", s.escrowBuildTask)
        cb.str("providedDependencies", s.escrowProvidedDependencies)
        cb.bool("reusable", s.escrowReusable)
        cb.bare("generation", s.escrowGeneration)
        cb.str("diskSpace", s.escrowDiskSpace)
        cb.str("additionalSources", s.escrowAdditionalSources)
        cb.str("gradleIncludeConfigurations", s.escrowGradleIncludeConfigurations)
        cb.str("gradleExcludeConfigurations", s.escrowGradleExcludeConfigurations)
        cb.bool("gradleIncludeTestConfigurations", s.escrowGradleIncludeTestConfigurations)
        cb.close()
    }

    private fun writeDocs(
        cb: CodeBuilder,
        component: ComponentEntity,
    ) {
        val docs = component.docLinks.sortedBy { it.sortOrder }
        if (docs.isEmpty()) return
        cb.open("docs")
        docs.forEach { d ->
            cb.open("doc")
            cb.str("component", d.docComponentKey)
            cb.str("majorVersion", d.majorVersion)
            cb.close()
        }
        cb.close()
    }

    // ============================================================
    // Per-range override block (FULL only)
    // ============================================================

    private fun writeRangeBlock(
        cb: CodeBuilder,
        range: String,
        rows: List<ComponentConfigurationEntity>,
    ) {
        val scalarOverrides = rows.filter { it.rowType == ROW_SCALAR_OVERRIDE }
        // Only render markers the editor model knows about. Import-internal markers
        // (e.g. MarkerAttributes.GROUP_ARTIFACT_PATTERN, deliberately not in `ALL`)
        // carry no renderable block — including them would emit an empty range block.
        val markers = rows.filter { it.rowType == ROW_MARKER && it.overriddenAttribute in MarkerAttributes.ALL }
        // RANGE_PRESENCE-only (or import-marker-only) ranges contribute nothing → skip (no empty block).
        if (scalarOverrides.isEmpty() && markers.isEmpty()) return

        cb.open(doubleQuoted(range))
        writeAspectOverrides(cb, "build", scalarOverrides, markers)
        writeAspectOverrides(cb, "jira", scalarOverrides, emptyList())
        writeAspectOverrides(cb, "escrow", scalarOverrides, emptyList())
        writeMarkerVcs(cb, markers)
        writeMarkerDistribution(cb, markers)
        cb.close()
    }

    private fun writeAspectOverrides(
        cb: CodeBuilder,
        aspect: String,
        scalarOverrides: List<ComponentConfigurationEntity>,
        markers: List<ComponentConfigurationEntity>,
    ) {
        val prefix = "$aspect."
        val scalars = scalarOverrides.filter { (it.overriddenAttribute ?: "").startsWith(prefix) }
            .sortedBy { it.overriddenAttribute }
        val toolMarker = markers.firstOrNull { it.overriddenAttribute == MarkerAttributes.BUILD_REQUIRED_TOOLS }
        val beanMarker = markers.firstOrNull { it.overriddenAttribute == MarkerAttributes.BUILD_TOOLS }
        val hasBuildMarkers = aspect == "build" && (toolMarker != null || beanMarker != null)
        if (scalars.isEmpty() && !hasBuildMarkers) return

        cb.open(aspect)
        scalars.forEach { row -> writeScalarOverrideLine(cb, row) }
        if (aspect == "build") {
            toolMarker?.let { cb.strList("requiredTools", it.requiredToolJunctions.map { j -> j.toolName }) }
            beanMarker?.let { writeBuildToolBeans(cb, it.buildToolBeans.toList()) }
        }
        cb.close()
    }

    /** A single SCALAR_OVERRIDE row → `field = value` (or `field = null` for a null-clear). */
    private fun writeScalarOverrideLine(
        cb: CodeBuilder,
        row: ComponentConfigurationEntity,
    ) {
        val path = row.overriddenAttribute ?: return
        val field = path.substringAfter('.')
        when (path) {
            // Bare enum tokens
            "build.buildSystem", "escrow.generation" -> cb.bareRaw(field, row.extractScalarValue()?.toString())
            // Booleans
            "build.deprecated", "build.requiredProject", "escrow.reusable",
            "escrow.gradleIncludeTestConfigurations", "jira.technical",
            -> cb.boolRaw(field, row.extractScalarValue() as? Boolean)
            // Everything else is a string-valued scalar
            else -> cb.strRaw(field, row.extractScalarValue()?.toString())
        }
    }

    private fun writeMarkerVcs(
        cb: CodeBuilder,
        markers: List<ComponentConfigurationEntity>,
    ) {
        val vcs = markers.firstOrNull { it.overriddenAttribute == MarkerAttributes.VCS_SETTINGS } ?: return
        // externalRegistry is per-component (not per-range), so range vcs blocks carry only roots.
        writeVcs(cb, vcs.vcsEntries.toList(), externalRegistry = null)
    }

    private fun writeMarkerDistribution(
        cb: CodeBuilder,
        markers: List<ComponentConfigurationEntity>,
    ) {
        val maven = markers.firstOrNull { it.overriddenAttribute == MarkerAttributes.DISTRIBUTION_MAVEN }
        val fileUrl = markers.firstOrNull { it.overriddenAttribute == MarkerAttributes.DISTRIBUTION_FILE_URL }
        val docker = markers.firstOrNull { it.overriddenAttribute == MarkerAttributes.DISTRIBUTION_DOCKER }
        val pkgs = markers.firstOrNull { it.overriddenAttribute == MarkerAttributes.DISTRIBUTION_PACKAGES }
        if (maven == null && fileUrl == null && docker == null && pkgs == null) return
        cb.open("distribution")
        maven?.mavenArtifacts?.sortedBy { it.sortOrder }?.forEach { m ->
            cb.open("maven")
            cb.str("groupPattern", m.groupPattern)
            cb.str("artifactPattern", m.artifactPattern)
            cb.str("extension", m.extension)
            cb.str("classifier", m.classifier)
            cb.close()
        }
        fileUrl?.fileUrlArtifacts?.sortedBy { it.sortOrder }?.forEach { f ->
            cb.open("fileUrl")
            cb.str("url", f.url)
            cb.str("artifactId", f.artifactId)
            cb.str("classifier", f.classifier)
            cb.close()
        }
        docker?.dockerImages?.sortedBy { it.sortOrder }?.forEach { d ->
            cb.open("docker")
            cb.str("imageName", d.imageName)
            cb.str("flavor", d.flavor)
            cb.close()
        }
        pkgs?.packages?.sortedBy { it.sortOrder }?.forEach { p ->
            cb.open("package")
            cb.bare("type", p.packageType)
            cb.str("name", p.packageName)
            cb.close()
        }
        cb.close()
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun <T> pickMarkerChildren(
        attribute: String,
        markerOverrides: List<ComponentConfigurationEntity>,
        baseChildren: List<T>,
        childExtractor: (ComponentConfigurationEntity) -> List<T>,
    ): List<T> {
        val marker = markerOverrides.firstOrNull { it.overriddenAttribute == attribute }
        return if (marker != null) childExtractor(marker) else baseChildren
    }

    /** Block header token: bare identifier when valid, quoted otherwise. */
    private fun blockHeader(name: String): String =
        if (name.matches(IDENTIFIER_RE)) name else doubleQuoted(name)

    private companion object {
        const val ROW_BASE = "BASE"
        const val ROW_SCALAR_OVERRIDE = "SCALAR_OVERRIDE"
        const val ROW_MARKER = "MARKER"
        val IDENTIFIER_RE = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

/**
 * Indentation-aware Groovy emitter. `open`/`close` manage brace blocks; the
 * typed `str` / `bool` / `bare` / `strList` helpers skip null/empty values so no
 * empty assignment or block is ever emitted. The `*Raw` variants always emit
 * (used for per-range overrides where a null value is a meaningful null-clear).
 */
internal class CodeBuilder {
    private val sb = StringBuilder()
    private var indent = 0

    fun open(header: String) {
        raw("$header {")
        indent++
    }

    fun close() {
        indent--
        raw("}")
    }

    /** String value — emitted only when non-null. */
    fun str(name: String, value: String?) {
        if (value != null) raw("$name = ${groovyString(value)}")
    }

    /** String value, always emitted (null → `null`). For per-range null-clear overrides. */
    fun strRaw(name: String, value: String?) {
        raw("$name = ${value?.let { groovyString(it) } ?: "null"}")
    }

    fun bool(name: String, value: Boolean?) {
        if (value != null) raw("$name = $value")
    }

    fun boolRaw(name: String, value: Boolean?) {
        raw("$name = ${value ?: "null"}")
    }

    /** Bare (enum) token — emitted only when non-null/blank. */
    fun bare(name: String, value: String?) {
        if (!value.isNullOrBlank()) raw("$name = $value")
    }

    fun bareRaw(name: String, value: String?) {
        raw("$name = ${value ?: "null"}")
    }

    /** List of strings — emitted only when non-empty. */
    fun strList(name: String, values: List<String>) {
        if (values.isNotEmpty()) raw("$name = ${groovyList(values)}")
    }

    private fun raw(text: String) {
        repeat(indent) { sb.append("    ") }
        sb.append(text).append('\n')
    }

    override fun toString(): String = sb.toString()
}

/** Quote a string Groovy-style; values containing `$` use single quotes so the literal is preserved. */
internal fun groovyString(s: String): String = if (s.contains('$')) singleQuoted(s) else doubleQuoted(s)

internal fun doubleQuoted(s: String): String =
    buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

internal fun singleQuoted(s: String): String =
    buildString {
        append('\'')
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('\'')
    }

internal fun groovyList(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { groovyString(it) }
