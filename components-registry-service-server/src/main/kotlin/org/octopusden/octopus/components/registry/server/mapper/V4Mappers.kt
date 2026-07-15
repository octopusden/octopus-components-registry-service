@file:Suppress("TooManyFunctions")

package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.server.dto.v4.ArtifactIdResponse
import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogResponse
import org.octopusden.octopus.components.registry.server.dto.v4.BuildAspectResponse
import org.octopusden.octopus.components.registry.server.dto.v4.BuildToolBeanRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BuildToolBeanResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentConfigurationResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentGroupResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentGroupRole
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ConfigurationRowType
import org.octopusden.octopus.components.registry.server.dto.v4.DocLinkResponse
import org.octopusden.octopus.components.registry.server.dto.v4.DockerImageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DockerImageResponse
import org.octopusden.octopus.components.registry.server.dto.v4.EscrowAspectResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FileUrlArtifactRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FileUrlArtifactResponse
import org.octopusden.octopus.components.registry.server.dto.v4.JiraAspectResponse
import org.octopusden.octopus.components.registry.server.dto.v4.MarkerChildrenPayload
import org.octopusden.octopus.components.registry.server.dto.v4.MavenArtifactRequest
import org.octopusden.octopus.components.registry.server.dto.v4.MavenArtifactResponse
import org.octopusden.octopus.components.registry.server.dto.v4.PackageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.PackageResponse
import org.octopusden.octopus.components.registry.server.dto.v4.SecurityGroupResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityProjectResponse
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryRequest
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryResponse
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity

/**
 * Maps a fully-loaded `ComponentEntity` (with `configurations` + per-component
 * children accessible in the Hibernate session) to the v4 detail view.
 *
 * `teamcityBaseUrl` is the `teamcity.base-url` service-config value; when
 * blank, `teamcityProjects[].projectUrl` is left null so callers can detect a
 * deliberately unconfigured TC.
 */
fun ComponentEntity.toDetailResponse(teamcityBaseUrl: String? = null): ComponentDetailResponse =
    ComponentDetailResponse(
        id = this.id!!,
        name = this.componentKey,
        displayName = this.displayName,
        componentOwner = this.componentOwner,
        productType = this.productType,
        systems = this.systemJunctions.map { it.systemCode }.toSet(),
        clientCode = this.clientCode,
        archived = this.archived,
        solution = this.solution,
        parentComponentName = this.parentComponent?.componentKey,
        canBeParent = this.canBeParent,
        version = this.version,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
        releaseManager = this.releaseManagerUsernames(),
        securityChampion = this.securityChampionUsernames(),
        copyright = this.copyright,
        releasesInDefaultBranch = this.releasesInDefaultBranch,
        labels = this.labelJunctions.map { it.labelCode }.toSet(),
        jiraDisplayName = this.jiraDisplayName,
        jiraHotfixVersionFormat = this.jiraHotfixVersionFormat,
        vcsExternalRegistry = this.vcsExternalRegistry,
        skipCommitCheck = this.skipCommitCheck,
        distributionExplicit = this.distributionExplicit,
        distributionExternal = this.distributionExternal,
        group = this.componentGroup?.let { group -> group.toResponse(thisComponentKey = this.componentKey) },
        docs = this.docLinks.sortedBy { it.sortOrder }.map { it.toResponse() },
        artifactIds = this.artifactMappings.sortedWith(ARTIFACT_MAPPING_ORDER).map { it.toResponse() },
        securityGroups = this.securityGroups.map { it.toResponse() },
        teamcityProjects =
            this.teamcityProjects
                .sortedBy { it.sortOrder }
                .map { tc ->
                    TeamcityProjectResponse(
                        id = tc.id!!,
                        projectId = tc.projectId,
                        projectUrl = composeTeamcityProjectUrl(teamcityBaseUrl, tc.projectId),
                        projectVersion = tc.projectVersion,
                        sortOrder = tc.sortOrder,
                    )
                },
        configurations =
            this.configurations
                .filter { it.rowType != "RANGE_PRESENCE" }
                .map { it.toConfigurationResponse() },
    )

/**
 * Compact list-view projection. SYS-040 fields (`buildSystem`, `javaVersion`,
 * `jiraProjectKey`, `vcsPath`, `teamcityProjectId`, `teamcityProjectUrl`) are
 * derived from the BASE configuration row (`row_type = 'BASE'`) and the first child
 * (`sort_order = 0`) so multi-VCS / multi-TC components render their primary
 * link the same way single-target components do. Blank strings → null.
 */
fun ComponentEntity.toSummaryResponse(teamcityBaseUrl: String? = null): ComponentSummaryResponse {
    val base = this.configurations.firstOrNull { it.rowType == "BASE" }
    val firstTcProject = this.teamcityProjects.minByOrNull { it.sortOrder }
    val firstVcsEntry = base?.vcsEntries?.minByOrNull { it.sortOrder }
    return ComponentSummaryResponse(
        id = this.id!!,
        name = this.componentKey,
        displayName = this.displayName,
        componentOwner = this.componentOwner,
        systems = this.systemJunctions.map { it.systemCode }.toSet(),
        productType = this.productType,
        archived = this.archived,
        canBeParent = this.canBeParent,
        updatedAt = this.updatedAt,
        labels = this.labelJunctions.map { it.labelCode },
        releaseManagers = this.releaseManagerUsernames(),
        securityChampions = this.securityChampionUsernames(),
        buildSystem = base?.buildSystem?.takeIf { it.isNotBlank() },
        javaVersion = base?.javaVersion?.takeIf { it.isNotBlank() },
        jiraProjectKey = base?.jiraProjectKey?.takeIf { it.isNotBlank() },
        vcsPath = firstVcsEntry?.vcsPath?.takeIf { it.isNotBlank() }?.sshUrlToProjectRepo(),
        teamcityProjectId = firstTcProject?.projectId,
        teamcityProjectUrl = firstTcProject?.let { composeTeamcityProjectUrl(teamcityBaseUrl, it.projectId) },
    )
}

/**
 * One row of `component_configurations` → v4 wire representation. The row's
 * shape is read from the stored `row_type` column (source of truth):
 *
 *  - BASE → all aspects + all child collections populated from this row
 *  - MARKER → no aspects; one child family populated (per `overriddenAttribute`)
 *  - SCALAR_OVERRIDE → one aspect with one field; no child collections
 *  - RANGE_PRESENCE → storage-only; never serialised (`toDetailResponse`
 *    filters these out before calling this function).
 *
 * For SCALAR_OVERRIDE rows the aspect prefix on `overriddenAttribute` selects
 * which aspect carries the single overridden field; the other two aspects are
 * null on the wire even though their corresponding entity columns are also
 * NULL (the invariant — only one column non-NULL per scalar override).
 */
fun ComponentConfigurationEntity.toConfigurationResponse(): ComponentConfigurationResponse {
    val rowType = classifyRowType()
    val build = if (shouldEmitBuildAspect(rowType)) buildAspectResponse() else null
    val escrow = if (shouldEmitEscrowAspect(rowType)) escrowAspectResponse() else null
    val jira = if (shouldEmitJiraAspect(rowType)) jiraAspectResponse() else null

    val vcs = pickChildRows(rowType, MarkerAttributes.VCS_SETTINGS) { vcsEntries.sortedBy { it.sortOrder }.map { it.toResponse() } }
    val maven = pickChildRows(rowType, MarkerAttributes.DISTRIBUTION_MAVEN) {
        mavenArtifacts.sortedBy { it.sortOrder }.map { it.toResponse() }
    }
    val fileUrl = pickChildRows(rowType, MarkerAttributes.DISTRIBUTION_FILE_URL) {
        fileUrlArtifacts.sortedBy { it.sortOrder }.map { it.toResponse() }
    }
    val docker = pickChildRows(rowType, MarkerAttributes.DISTRIBUTION_DOCKER) {
        dockerImages.sortedBy { it.sortOrder }.map { it.toResponse() }
    }
    val packages = pickChildRows(rowType, MarkerAttributes.DISTRIBUTION_PACKAGES) {
        this.packages.sortedBy { it.sortOrder }.map { it.toResponse() }
    }
    val tools = pickChildRows(rowType, MarkerAttributes.BUILD_REQUIRED_TOOLS) { requiredToolJunctions.map { it.toolName } }
    val buildBeans = pickChildRows(rowType, MarkerAttributes.BUILD_TOOLS) {
        buildToolBeans.sortedBy { it.sortOrder }.map { it.toBuildToolBeanResponse() }
    }

    return ComponentConfigurationResponse(
        id = this.id!!,
        versionRange = this.versionRange,
        rowType = rowType,
        overriddenAttribute = this.overriddenAttribute,
        isSyntheticBase = this.isSyntheticBase,
        build = build,
        escrow = escrow,
        jira = jira,
        vcsEntries = vcs,
        mavenArtifacts = maven,
        fileUrlArtifacts = fileUrl,
        dockerImages = docker,
        packages = packages,
        requiredTools = tools,
        buildToolBeans = buildBeans,
    )
}

/**
 * One `component_configurations` row → field-override view. The wire shape is
 * the union form used by `POST /field-overrides`: scalar rows carry `value`,
 * marker rows carry `markerChildren` (with the one matching child list).
 */
fun ComponentConfigurationEntity.toFieldOverrideResponse(): FieldOverrideResponse {
    val rowType = classifyRowType()
    require(rowType != ConfigurationRowType.BASE && rowType != ConfigurationRowType.RANGE_PRESENCE) {
        "Only SCALAR_OVERRIDE / MARKER rows can be projected as field overrides (got $rowType)"
    }
    val markerChildren =
        if (rowType == ConfigurationRowType.MARKER) toMarkerChildrenPayload() else null
    return FieldOverrideResponse(
        id = this.id!!,
        overriddenAttribute = this.overriddenAttribute!!,
        versionRange = this.versionRange,
        rowType = rowType,
        value = if (rowType == ConfigurationRowType.SCALAR_OVERRIDE) extractScalarValue() else null,
        markerChildren = markerChildren,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )
}

// `componentKey` is resolved by the service layer (which owns the component
// repository) and threaded in here; the mapper itself stays a pure projection
// with no DB access. Defaults to null so the few non-audit callers / tests that
// don't resolve a key still compile.
fun AuditLogEntity.toResponse(componentKey: String? = null): AuditLogResponse =
    AuditLogResponse(
        id = this.id!!,
        entityType = this.entityType,
        entityId = this.entityId,
        action = this.action,
        changedBy = this.changedBy,
        changedAt = this.changedAt,
        oldValue = this.oldValue,
        newValue = this.newValue,
        changeDiff = this.changeDiff,
        correlationId = this.correlationId,
        source = this.source,
        jiraTaskKey = this.jiraTaskKey,
        changeComment = this.changeComment,
        componentKey = componentKey,
    )

// ============================================================
// Internal: row classification and per-aspect/per-child helpers
// ============================================================

private fun ComponentConfigurationEntity.classifyRowType(): ConfigurationRowType = ConfigurationRowType.valueOf(this.rowType)

private fun ComponentConfigurationEntity.shouldEmitBuildAspect(rowType: ConfigurationRowType): Boolean =
    rowType == ConfigurationRowType.BASE ||
        (rowType == ConfigurationRowType.SCALAR_OVERRIDE && overriddenAttribute!!.startsWith("build."))

private fun ComponentConfigurationEntity.shouldEmitEscrowAspect(rowType: ConfigurationRowType): Boolean =
    rowType == ConfigurationRowType.BASE ||
        (rowType == ConfigurationRowType.SCALAR_OVERRIDE && overriddenAttribute!!.startsWith("escrow."))

private fun ComponentConfigurationEntity.shouldEmitJiraAspect(rowType: ConfigurationRowType): Boolean =
    rowType == ConfigurationRowType.BASE ||
        (rowType == ConfigurationRowType.SCALAR_OVERRIDE && overriddenAttribute!!.startsWith("jira."))

private fun <T> ComponentConfigurationEntity.pickChildRows(
    rowType: ConfigurationRowType,
    markerName: String,
    extractor: ComponentConfigurationEntity.() -> List<T>,
): List<T> =
    when {
        rowType == ConfigurationRowType.BASE -> this.extractor()
        rowType == ConfigurationRowType.MARKER && overriddenAttribute == markerName -> this.extractor()
        else -> emptyList()
    }

private fun ComponentConfigurationEntity.buildAspectResponse(): BuildAspectResponse =
    BuildAspectResponse(
        buildSystem = buildSystem,
        javaVersion = javaVersion,
        mavenVersion = mavenVersion,
        gradleVersion = gradleVersion,
        buildFilePath = buildFilePath,
        deprecated = deprecated,
        requiredProject = requiredProject,
        projectVersion = projectVersion,
        systemProperties = systemProperties,
        buildTasks = buildTasks,
    )

private fun ComponentConfigurationEntity.escrowAspectResponse(): EscrowAspectResponse =
    EscrowAspectResponse(
        providedDependencies = escrowProvidedDependencies,
        reusable = escrowReusable,
        generation = escrowGeneration,
        diskSpace = escrowDiskSpace,
        additionalSources = escrowAdditionalSources,
        gradleIncludeConfigurations = escrowGradleIncludeConfigurations,
        gradleExcludeConfigurations = escrowGradleExcludeConfigurations,
        gradleIncludeTestConfigurations = escrowGradleIncludeTestConfigurations,
        buildTask = escrowBuildTask,
    )

private fun ComponentConfigurationEntity.jiraAspectResponse(): JiraAspectResponse =
    JiraAspectResponse(
        projectKey = jiraProjectKey,
        technical = jiraTechnical,
        minorVersionFormat = jiraMinorVersionFormat,
        releaseVersionFormat = jiraReleaseVersionFormat,
        buildVersionFormat = jiraBuildVersionFormat,
        lineVersionFormat = jiraLineVersionFormat,
        versionPrefix = jiraVersionPrefix,
        versionFormat = jiraVersionFormat,
        hotfixVersionFormat = jiraHotfixVersionFormat,
    )

private fun ComponentConfigurationEntity.toMarkerChildrenPayload(): MarkerChildrenPayload =
    when (overriddenAttribute) {
        MarkerAttributes.VCS_SETTINGS ->
            MarkerChildrenPayload(
                vcsEntries =
                    vcsEntries.sortedBy { it.sortOrder }.map { e ->
                        VcsEntryRequest(
                            name = e.name,
                            vcsPath = e.vcsPath,
                            branch = e.branch,
                            tag = e.tag,
                            hotfixBranch = e.hotfixBranch,
                            repositoryType = e.repositoryType,
                        )
                    },
            )

        MarkerAttributes.DISTRIBUTION_MAVEN,
        MarkerAttributes.GROUP_ARTIFACT_PATTERN,
        ->
            // GROUP_ARTIFACT_PATTERN (MIG-047) is import-internal and stays out of
            // MarkerAttributes.ALL — so it cannot be created/edited via the V4 POST
            // endpoint — but it shares the same `mavenArtifacts` child-collection
            // shape as DISTRIBUTION_MAVEN. listFieldOverrides projects it identically
            // so the V4 admin UI never throws on components that contain it.
            MarkerChildrenPayload(
                mavenArtifacts =
                    mavenArtifacts.sortedBy { it.sortOrder }.map { e ->
                        MavenArtifactRequest(
                            groupPattern = e.groupPattern,
                            artifactPattern = e.artifactPattern,
                            extension = e.extension,
                            classifier = e.classifier,
                        )
                    },
            )

        MarkerAttributes.DISTRIBUTION_FILE_URL ->
            MarkerChildrenPayload(
                fileUrlArtifacts =
                    fileUrlArtifacts.sortedBy { it.sortOrder }.map { e ->
                        FileUrlArtifactRequest(
                            url = e.url,
                            artifactId = e.artifactId,
                            classifier = e.classifier,
                        )
                    },
            )

        MarkerAttributes.DISTRIBUTION_DOCKER ->
            MarkerChildrenPayload(
                dockerImages =
                    dockerImages.sortedBy { it.sortOrder }.map { e ->
                        DockerImageRequest(
                            imageName = e.imageName,
                            flavor = e.flavor,
                        )
                    },
            )

        MarkerAttributes.DISTRIBUTION_PACKAGES ->
            MarkerChildrenPayload(
                packages =
                    packages.sortedBy { it.sortOrder }.map { e ->
                        PackageRequest(
                            packageType = e.packageType,
                            packageName = e.packageName,
                        )
                    },
            )

        MarkerAttributes.BUILD_REQUIRED_TOOLS ->
            MarkerChildrenPayload(requiredTools = requiredToolJunctions.map { it.toolName })

        MarkerAttributes.BUILD_TOOLS ->
            MarkerChildrenPayload(
                buildToolBeans =
                    buildToolBeans.sortedBy { it.sortOrder }.map { it.toBuildToolBeanRequest() },
            )

        else -> error("Marker row has unknown overriddenAttribute '$overriddenAttribute'")
    }

// ============================================================
// Internal: child-row → DTO converters
// ============================================================

private fun org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity.toResponse(): VcsEntryResponse =
    VcsEntryResponse(
        id = this.id!!,
        name = this.name,
        vcsPath = this.vcsPath,
        branch = this.branch,
        tag = this.tag,
        hotfixBranch = this.hotfixBranch,
        repositoryType = this.repositoryType,
        sortOrder = this.sortOrder,
    )

private fun org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity.toResponse(): MavenArtifactResponse =
    MavenArtifactResponse(
        id = this.id!!,
        groupPattern = this.groupPattern,
        artifactPattern = this.artifactPattern,
        extension = this.extension,
        classifier = this.classifier,
        sortOrder = this.sortOrder,
    )

private fun org.octopusden.octopus.components.registry.server.entity.DistributionFileUrlArtifactEntity.toResponse(): FileUrlArtifactResponse =
    FileUrlArtifactResponse(
        id = this.id!!,
        url = this.url,
        artifactId = this.artifactId,
        classifier = this.classifier,
        sortOrder = this.sortOrder,
    )

private fun org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity.toResponse(): DockerImageResponse =
    DockerImageResponse(
        id = this.id!!,
        imageName = this.imageName,
        flavor = this.flavor,
        sortOrder = this.sortOrder,
    )

private fun org.octopusden.octopus.components.registry.server.entity.DistributionPackageEntity.toResponse(): PackageResponse =
    PackageResponse(
        id = this.id!!,
        packageType = this.packageType,
        packageName = this.packageName,
        sortOrder = this.sortOrder,
    )

private fun org.octopusden.octopus.components.registry.server.entity.ComponentDocLinkEntity.toResponse(): DocLinkResponse =
    DocLinkResponse(
        id = this.id!!,
        docComponentKey = this.docComponentKey,
        majorVersion = this.majorVersion,
        sortOrder = this.sortOrder,
    )

/**
 * Deterministic ownership-mapping order for v4 detail + audit snapshots: base (ALL_VERSIONS) first,
 * then by version range, then sortOrder, then id. Base and per-range buckets both start at
 * sortOrder=0, so sortOrder alone is load-order-dependent; this comparator is stable regardless.
 */
internal val ARTIFACT_MAPPING_ORDER: Comparator<ComponentArtifactMappingEntity> =
    compareBy(
        { it.versionRange != ALL_VERSIONS },
        { it.versionRange },
        { it.sortOrder },
        { it.id?.toString() ?: "" },
    )

private fun org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity.toResponse(): ArtifactIdResponse {
    val mode = org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
        .valueOf(this.artifactIdMode)
    val tokens = this.tokens.sortedBy { it.sortOrder }.map { it.artifactPattern }
    return ArtifactIdResponse(
        id = this.id!!,
        versionRange = this.versionRange,
        groupPattern = this.groupPattern,
        mode = this.artifactIdMode,
        artifactTokens = tokens,
        legacyArtifactIdPattern =
            org.octopusden.octopus.components.registry.server.util.ArtifactOwnershipRendering
                .renderArtifactPattern(mode, tokens),
    )
}

private fun org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity.toResponse(): SecurityGroupResponse =
    SecurityGroupResponse(
        id = this.id!!,
        groupType = this.groupType,
        groupName = this.groupName,
    )

private fun ComponentBuildToolBeanEntity.toBuildToolBeanResponse(): BuildToolBeanResponse =
    BuildToolBeanResponse(
        id = this.id!!,
        beanType = this.beanType,
        toolType = this.toolType,
        settingsProperty = this.settingsProperty,
        versionPattern = this.versionPattern,
        edition = this.edition,
        sortOrder = this.sortOrder,
    )

private fun ComponentBuildToolBeanEntity.toBuildToolBeanRequest(): BuildToolBeanRequest =
    BuildToolBeanRequest(
        beanType = this.beanType,
        toolType = this.toolType,
        settingsProperty = this.settingsProperty,
        versionPattern = this.versionPattern,
        edition = this.edition,
    )

private fun org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity.toResponse(
    thisComponentKey: String,
): ComponentGroupResponse =
    ComponentGroupResponse(
        groupKey = this.groupKey,
        isFake = this.isFake,
        // The aggregator's own components row carries componentKey == groupKey
        // (schema-spec.md §3.2). Members share the group but have a different key.
        role = if (this.groupKey == thisComponentKey) ComponentGroupRole.AGGREGATOR else ComponentGroupRole.MEMBER,
    )

// ============================================================
// Internal: TeamCity URL composition
// ============================================================

/**
 * Compose `<teamcity-base>/project/<project_id>` for a component's TC project
 * pointer. Returns null when the base URL is blank (deliberately unconfigured
 * env) so the caller can render "TC not configured" rather than a broken link.
 *
 * Tolerates a trailing slash on `base` (TC's UI does the same).
 */
internal fun composeTeamcityProjectUrl(
    base: String?,
    projectId: String,
): String? {
    if (base.isNullOrBlank()) return null
    val trimmed = base.trimEnd('/')
    return "$trimmed/project/$projectId"
}

// ============================================================
// Internal: SSH URL → "project/repo" normalisation (kept from prior version)
// ============================================================

/**
 * Normalises a raw VCS path to the `project/repo` format expected by the
 * Portal for constructing Bitbucket (and future Gitea) browse links.
 *
 * Handles:
 *  - `ssh://git@host/[scm/]project/repo.git`    (Bitbucket ssh scheme, no port)
 *  - `ssh://git@host:port/[scm/]project/repo.git` (Bitbucket, numeric port)
 *  - `ssh://git@host:org/repo.git`              (GitHub SCP-over-SSH, org not a port)
 *  - `git@host:project/repo.git`               (SCP-style git, Gitea / Bitbucket)
 *  - `project/repo`                            (already normalised, returned as-is)
 *
 * If the value does not match any known pattern the original string is
 * returned unchanged so the Portal can decide how to handle it.
 */
internal fun String.sshUrlToProjectRepo(): String {
    val pathPart: String =
        when {
            startsWith("ssh://git@") -> {
                val afterAt = substringAfter("@")
                val colonIdx = afterAt.indexOf(':')
                if (colonIdx >= 0) {
                    val portOrOrg = afterAt.substring(colonIdx + 1).substringBefore("/")
                    if (portOrOrg.isNotEmpty() && portOrOrg.all { it.isDigit() }) {
                        afterAt.substringAfter("/")
                    } else {
                        afterAt.substringAfter(":")
                    }
                } else {
                    afterAt.substringAfter("/")
                }
            }
            startsWith("git@") -> substringAfter(":")
            else -> return this
        }
    val cleaned = pathPart.trimEnd('/').removeSuffix(".git")
    val normalized = if (cleaned.startsWith("scm/")) cleaned.removePrefix("scm/") else cleaned
    val parts = normalized.split("/").filter { it.isNotEmpty() }
    return if (parts.size >= 2) "${parts[parts.size - 2]}/${parts.last()}" else this
}
