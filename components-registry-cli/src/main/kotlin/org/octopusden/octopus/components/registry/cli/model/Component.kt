package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.Serializable

/**
 * Mirror of v4.json `ComponentSummaryResponse` — one row of the components list page.
 *
 * Required per spec: archived, canBeParent, id, labels, name.
 */
@Serializable
data class ComponentSummaryResponse(
    val id: String,
    val name: String,
    val archived: Boolean,
    val canBeParent: Boolean,
    val labels: List<String>,
    val displayName: String? = null,
    val system: String? = null,
    val productType: String? = null,
    val componentOwner: String? = null,
    val buildSystem: String? = null,
    val jiraProjectKey: String? = null,
    val vcsPath: String? = null,
    val teamcityProjectId: String? = null,
    val teamcityProjectUrl: String? = null,
    val updatedAt: String? = null,
)

/**
 * Mirror of v4.json `ComponentGroupResponse`. `role` is the enum AGGREGATOR|MEMBER (kept as String).
 *
 * Required per spec: groupKey, isFake, role.
 */
@Serializable
data class ComponentGroupResponse(
    val groupKey: String,
    val isFake: Boolean,
    val role: String,
)

/**
 * Mirror of v4.json `ArtifactIdResponse`. Required: artifactPattern, groupPattern, id.
 */
@Serializable
data class ArtifactIdResponse(
    val artifactPattern: String,
    val groupPattern: String,
    val id: String,
)

/**
 * Mirror of v4.json `DocLinkResponse`. Required: docComponentKey, id, sortOrder.
 */
@Serializable
data class DocLinkResponse(
    val docComponentKey: String,
    val id: String,
    val sortOrder: Int,
    val majorVersion: String? = null,
)

/**
 * Mirror of v4.json `SecurityGroupResponse`. Required: groupName, groupType, id.
 */
@Serializable
data class SecurityGroupResponse(
    val groupName: String,
    val groupType: String,
    val id: String,
)

/**
 * Mirror of v4.json `TeamcityProjectResponse`. Required: id, projectId, sortOrder.
 */
@Serializable
data class TeamcityProjectResponse(
    val id: String,
    val projectId: String,
    val sortOrder: Int,
    val projectUrl: String? = null,
)

/**
 * Mirror of v4.json `BuildAspectResponse`. No required fields per spec; all-nullable.
 */
@Serializable
data class BuildAspectResponse(
    val buildFilePath: String? = null,
    val buildSystem: String? = null,
    val buildTasks: String? = null,
    val deprecated: Boolean? = null,
    val gradleVersion: String? = null,
    val javaVersion: String? = null,
    val mavenVersion: String? = null,
    val projectVersion: String? = null,
    val requiredProject: Boolean? = null,
    val systemProperties: String? = null,
)

/**
 * Mirror of v4.json `EscrowAspectResponse`. No required fields per spec; all-nullable.
 */
@Serializable
data class EscrowAspectResponse(
    val additionalSources: String? = null,
    val buildTask: String? = null,
    val diskSpace: String? = null,
    val generation: String? = null,
    val gradleExcludeConfigurations: String? = null,
    val gradleIncludeConfigurations: String? = null,
    val gradleIncludeTestConfigurations: Boolean? = null,
    val providedDependencies: String? = null,
    val reusable: Boolean? = null,
)

/**
 * Mirror of v4.json `JiraAspectResponse`. No required fields per spec; all-nullable.
 */
@Serializable
data class JiraAspectResponse(
    val buildVersionFormat: String? = null,
    val hotfixVersionFormat: String? = null,
    val lineVersionFormat: String? = null,
    val majorVersionFormat: String? = null,
    val projectKey: String? = null,
    val releaseVersionFormat: String? = null,
    val technical: Boolean? = null,
    val versionFormat: String? = null,
    val versionPrefix: String? = null,
)

/**
 * Mirror of v4.json `BuildToolBeanResponse`. Required: beanType, id, sortOrder.
 */
@Serializable
data class BuildToolBeanResponse(
    val beanType: String,
    val id: String,
    val sortOrder: Int,
    val edition: String? = null,
    val settingsProperty: String? = null,
    val toolType: String? = null,
    val versionPattern: String? = null,
)

/**
 * Mirror of v4.json `DockerImageResponse`. Required: id, imageName, sortOrder.
 */
@Serializable
data class DockerImageResponse(
    val id: String,
    val imageName: String,
    val sortOrder: Int,
    val flavor: String? = null,
)

/**
 * Mirror of v4.json `FileUrlArtifactResponse`. Required: id, sortOrder, url.
 */
@Serializable
data class FileUrlArtifactResponse(
    val id: String,
    val sortOrder: Int,
    val url: String,
    val artifactId: String? = null,
    val classifier: String? = null,
)

/**
 * Mirror of v4.json `MavenArtifactResponse`. Required: artifactPattern, groupPattern, id, sortOrder.
 */
@Serializable
data class MavenArtifactResponse(
    val artifactPattern: String,
    val groupPattern: String,
    val id: String,
    val sortOrder: Int,
    val classifier: String? = null,
    val extension: String? = null,
)

/**
 * Mirror of v4.json `PackageResponse`. Required: id, packageName, packageType, sortOrder.
 */
@Serializable
data class PackageResponse(
    val id: String,
    val packageName: String,
    val packageType: String,
    val sortOrder: Int,
)

/**
 * Mirror of v4.json `VcsEntryResponse`. Required: id, sortOrder, vcsPath.
 */
@Serializable
data class VcsEntryResponse(
    val id: String,
    val sortOrder: Int,
    val vcsPath: String,
    val name: String? = null,
    val repositoryType: String? = null,
    val branch: String? = null,
    val tag: String? = null,
    val hotfixBranch: String? = null,
)

/**
 * Mirror of v4.json `ComponentConfigurationResponse` — one configuration row (BASE/override/marker)
 * of a component detail. `rowType` is the enum BASE|SCALAR_OVERRIDE|MARKER|RANGE_PRESENCE (String).
 *
 * Required per spec: buildToolBeans, dockerImages, fileUrlArtifacts, id, isSyntheticBase,
 * mavenArtifacts, packages, requiredTools, rowType, vcsEntries, versionRange.
 */
@Serializable
data class ComponentConfigurationResponse(
    val id: String,
    val isSyntheticBase: Boolean,
    val rowType: String,
    val versionRange: String,
    val requiredTools: List<String>,
    val buildToolBeans: List<BuildToolBeanResponse>,
    val dockerImages: List<DockerImageResponse>,
    val fileUrlArtifacts: List<FileUrlArtifactResponse>,
    val mavenArtifacts: List<MavenArtifactResponse>,
    val packages: List<PackageResponse>,
    val vcsEntries: List<VcsEntryResponse>,
    val build: BuildAspectResponse? = null,
    val escrow: EscrowAspectResponse? = null,
    val jira: JiraAspectResponse? = null,
    val overriddenAttribute: String? = null,
)

/**
 * Mirror of v4.json `ComponentDetailResponse` — the body returned by GET /rest/api/4/components/{id}
 * (and on create). `createdAt`/`updatedAt` are ISO-8601 date-time strings; `version` is the optimistic
 * lock counter (int64).
 *
 * Required per spec: archived, artifactIds, canBeParent, configurations, docs, id, labels, name,
 * releaseManager, securityChampion, securityGroups, teamcityProjects, version.
 */
@Serializable
data class ComponentDetailResponse(
    val id: String,
    val name: String,
    val archived: Boolean,
    val canBeParent: Boolean,
    val version: Long,
    val labels: List<String>,
    val artifactIds: List<ArtifactIdResponse>,
    val configurations: List<ComponentConfigurationResponse>,
    val docs: List<DocLinkResponse>,
    val releaseManager: List<String>,
    val securityChampion: List<String>,
    val securityGroups: List<SecurityGroupResponse>,
    val teamcityProjects: List<TeamcityProjectResponse>,
    val canEdit: Boolean? = null,
    val clientCode: String? = null,
    val componentOwner: String? = null,
    val copyright: String? = null,
    val createdAt: String? = null,
    val displayName: String? = null,
    val distributionExplicit: Boolean? = null,
    val distributionExternal: Boolean? = null,
    val group: ComponentGroupResponse? = null,
    val jiraDisplayName: String? = null,
    val jiraHotfixVersionFormat: String? = null,
    val parentComponentName: String? = null,
    val productType: String? = null,
    val releasesInDefaultBranch: Boolean? = null,
    val solution: Boolean? = null,
    val system: String? = null,
    val updatedAt: String? = null,
    val vcsExternalRegistry: String? = null,
)
