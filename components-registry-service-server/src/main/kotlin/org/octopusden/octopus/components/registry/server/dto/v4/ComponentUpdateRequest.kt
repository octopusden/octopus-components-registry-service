package org.octopusden.octopus.components.registry.server.dto.v4

data class ComponentUpdateRequest(
    val version: Long, // optimistic locking
    val displayName: String? = null,
    val componentOwner: String? = null,
    val productType: String? = null,
    val system: Set<String>? = null,
    val clientCode: String? = null,
    val solution: Boolean? = null,
    val parentComponentName: String? = null,
    val archived: Boolean? = null,
    val metadata: Map<String, Any?>? = null,
    val buildConfiguration: BuildConfigurationUpdateRequest? = null,
    val vcsSettings: VcsSettingsUpdateRequest? = null,
    val distribution: DistributionUpdateRequest? = null,
    val jiraComponentConfig: JiraComponentConfigUpdateRequest? = null,
    val escrowConfiguration: EscrowConfigurationUpdateRequest? = null,
)

data class BuildConfigurationUpdateRequest(
    val buildSystem: String? = null,
    val buildFilePath: String? = null,
    val javaVersion: String? = null,
    val deprecated: Boolean? = null,
    val metadata: Map<String, Any?>? = null,
)

data class VcsSettingsUpdateRequest(
    val vcsType: String? = null,
    val externalRegistry: String? = null,
    val entries: List<VcsSettingsEntryUpdateRequest>? = null,
)

data class VcsSettingsEntryUpdateRequest(
    val id: String? = null,
    val name: String? = null,
    val vcsPath: String? = null,
    val repositoryType: String? = null,
    val tag: String? = null,
    val branch: String? = null,
)

data class DistributionUpdateRequest(
    val explicit: Boolean? = null,
    val external: Boolean? = null,
    val artifacts: List<DistributionArtifactUpdateRequest>? = null,
    val securityGroups: List<DistributionSecurityGroupUpdateRequest>? = null,
)

data class DistributionArtifactUpdateRequest(
    val id: String? = null,
    val artifactType: String? = null,
    val groupPattern: String? = null,
    val artifactPattern: String? = null,
    val name: String? = null,
    val tag: String? = null,
)

data class DistributionSecurityGroupUpdateRequest(
    val id: String? = null,
    val groupType: String? = null,
    val groupName: String? = null,
)

data class JiraComponentConfigUpdateRequest(
    val projectKey: String? = null,
    val displayName: String? = null,
    val componentVersionFormat: Map<String, Any?>? = null,
    val technical: Boolean? = null,
    val metadata: Map<String, Any?>? = null,
)

data class EscrowConfigurationUpdateRequest(
    val buildTask: String? = null,
    val providedDependencies: String? = null,
    val reusable: Boolean? = null,
    val generation: String? = null,
    val diskSpace: String? = null,
    val metadata: Map<String, Any?>? = null,
)
