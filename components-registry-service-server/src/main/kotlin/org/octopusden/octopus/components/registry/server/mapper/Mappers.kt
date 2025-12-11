package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.api.escrow.Escrow
import org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentInfoDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentVersionFormatDTO
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.DocDTO
import org.octopusden.octopus.components.registry.core.dto.EscrowDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.RepositoryType
import org.octopusden.octopus.components.registry.core.dto.SecurityGroupsDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import org.octopusden.octopus.components.registry.core.dto.VersionControlSystemRootDTO
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.dto.ComponentArtifactConfiguration
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.Doc
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.octopus.releng.dto.JiraComponentVersion

fun JiraComponentVersion.toDTO(): JiraComponentVersionDTO {
    return JiraComponentVersionDTO(componentVersion.componentName, componentVersion.version, component.toDTO())
}

fun JiraComponent.toDTO(): JiraComponentDTO {
    val cvFormat = ComponentVersionFormatDTO(
        componentVersionFormat.majorVersionFormat, componentVersionFormat.releaseVersionFormat,
        componentVersionFormat.buildVersionFormat, componentVersionFormat.lineVersionFormat,
        componentVersionFormat.hotfixVersionFormat
    )
    val componentInfo = componentInfo.let { ComponentInfoDTO(it.versionPrefix ?: "", it.versionFormat ?: "") }
    return JiraComponentDTO(projectKey, displayName, cvFormat, componentInfo, isTechnical)
}

fun JiraComponentVersionRange.toDTO() = JiraComponentVersionRangeDTO(
    componentName,
    versionRange,
    component.toDTO(),
    distribution?.toDTO() ?: DistributionDTO(false, false, securityGroups = SecurityGroupsDTO()),
    vcsSettings.toDTO()
)

fun Distribution.toDTO() = DistributionDTO(
    explicit(),
    external(),
    GAV(),
    DEB(),
    RPM(),
    SecurityGroupsDTO(securityGroups?.read?.split(",")?.toList() ?: emptyList()),
    docker()
)


fun VCSSettings.toDTO(): VCSSettingsDTO {
    val vcsRoots = versionControlSystemRoots.map {
        it.toDTO()
    }
    return VCSSettingsDTO(vcsRoots, externalRegistry)
}

fun VersionControlSystemRoot.toDTO(): VersionControlSystemRootDTO {
    return VersionControlSystemRootDTO(name, vcsPath, RepositoryType.valueOf(repositoryType.name), tag, branch, hotfixBranch)
}

fun ComponentArtifactConfiguration.toDTO(): ComponentArtifactConfigurationDTO =
    ComponentArtifactConfigurationDTO(this.groupPattern, this.artifactPattern)

fun EscrowGenerationMode.toDTO(): org.octopusden.octopus.components.registry.core.dto.EscrowGenerationMode =
    when (this) {
        EscrowGenerationMode.AUTO -> org.octopusden.octopus.components.registry.core.dto.EscrowGenerationMode.AUTO
        EscrowGenerationMode.MANUAL -> org.octopusden.octopus.components.registry.core.dto.EscrowGenerationMode.MANUAL
        EscrowGenerationMode.UNSUPPORTED -> org.octopusden.octopus.components.registry.core.dto.EscrowGenerationMode.UNSUPPORTED
    }

fun Escrow.toDTO(): EscrowDTO =
    EscrowDTO(
        this.buildTask,
        this.providedDependencies.toList(),
        this.diskSpaceRequirement.orElse(null),
        this.additionalSources.toList(),
        this.isReusable,
        this.generation.toDTO()
    )

fun Doc.toDTO(): DocDTO =
    DocDTO(
        this.component(),
        this.majorVersion()
    )