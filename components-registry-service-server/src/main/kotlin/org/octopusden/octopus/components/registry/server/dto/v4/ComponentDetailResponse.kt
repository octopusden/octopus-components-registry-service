package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant
import java.util.UUID

data class ComponentDetailResponse(
    val id: UUID,
    val name: String,
    val displayName: String?,
    val componentOwner: String?,
    val productType: String?,
    val system: Set<String>,
    val clientCode: String?,
    val archived: Boolean,
    val solution: Boolean?,
    val parentComponentName: String?,
    val metadata: Map<String, Any?>,
    val version: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    // SYS-039: §7.0 Wave 2 PR-G fields. All optional with null / empty
    // defaults so legacy clients constructed before SYS-039 still
    // deserialize cleanly when CRS responses include the new fields.
    val groupId: String? = null,
    val releaseManager: String? = null,
    val securityChampion: String? = null,
    val copyright: String? = null,
    val releasesInDefaultBranch: Boolean? = null,
    val labels: Set<String> = emptySet(),
    val buildConfigurations: List<BuildConfigurationResponse> = emptyList(),
    val vcsSettings: List<VcsSettingsResponse> = emptyList(),
    val distributions: List<DistributionResponse> = emptyList(),
    val jiraComponentConfigs: List<JiraComponentConfigResponse> = emptyList(),
    val escrowConfigurations: List<EscrowConfigurationResponse> = emptyList(),
    val versions: List<ComponentVersionResponse> = emptyList(),
)

data class BuildConfigurationResponse(
    val id: UUID?,
    val buildSystem: String?,
    val buildFilePath: String?,
    val javaVersion: String?,
    val deprecated: Boolean,
    val metadata: Map<String, Any?>,
)

data class VcsSettingsResponse(
    val id: UUID?,
    val vcsType: String?,
    val externalRegistry: String?,
    val entries: List<VcsSettingsEntryResponse>,
)

data class VcsSettingsEntryResponse(
    val id: UUID?,
    val name: String?,
    val vcsPath: String?,
    val repositoryType: String,
    val tag: String?,
    val branch: String?,
)

data class DistributionResponse(
    val id: UUID?,
    val explicit: Boolean,
    val external: Boolean,
    val artifacts: List<DistributionArtifactResponse>,
    val securityGroups: List<DistributionSecurityGroupResponse>,
)

data class DistributionArtifactResponse(
    val id: UUID?,
    val artifactType: String,
    val groupPattern: String?,
    val artifactPattern: String?,
    val name: String?,
    val tag: String?,
)

data class DistributionSecurityGroupResponse(
    val id: UUID?,
    val groupType: String,
    val groupName: String,
)

data class JiraComponentConfigResponse(
    val id: UUID?,
    val projectKey: String?,
    val displayName: String?,
    val componentVersionFormat: Map<String, Any?>?,
    val technical: Boolean,
    val metadata: Map<String, Any?>,
)

data class EscrowConfigurationResponse(
    val id: UUID?,
    val buildTask: String?,
    val providedDependencies: String?,
    val reusable: Boolean?,
    val generation: String?,
    val diskSpace: String?,
)

data class ComponentVersionResponse(
    val id: UUID?,
    val versionRange: String,
)
