package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.distribution.DistributionEntity
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.dto.ComponentArtifactConfiguration
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import java.util.EnumMap

interface ComponentRegistryResolver {

    fun updateCache()

    fun getComponents(): MutableCollection<EscrowModule>

    fun getComponentById(id: String): EscrowModule?

    fun getResolvedComponentDefinition(id: String, version: String): EscrowModuleConfig?

    fun getJiraComponentVersion(component: String, version: String): JiraComponentVersion

    fun getJiraComponentVersions(component: String, versions: List<String>): Map<String, JiraComponentVersion>

    fun getVCSSettings(component: String, version: String): VCSSettings

    fun getBuildTools(component: String, version: String, ignoreRequired: Boolean): List<BuildTool>

    fun getDistributionEntities(component: String, version: String): List<DistributionEntity>

    fun getJiraComponentByProjectAndVersion(projectKey: String, version: String): JiraComponentVersion

    fun getJiraComponentsByProject(projectKey: String): Set<String>

    fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRange>

    fun getComponentsDistributionByJiraProject(projectKey: String): Map<String, Distribution>

    fun getVCSSettingForProject(projectKey: String, version: String): VCSSettings

    fun getDistributionForProject(projectKey: String, version: String): Distribution

    fun getAllJiraComponentVersionRanges(): Set<JiraComponentVersionRange>

    fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent

    fun findComponentsByArtifact(artifacts: Set<ArtifactDependency>): Map<ArtifactDependency, VersionedComponent?>

    fun getMavenArtifactParameters(component: String): Map<String, ComponentArtifactConfiguration>

    fun getDependencyMapping(): Map<String, String>

    fun getComponentsCountByBuildSystem(): EnumMap<BuildSystem, Int>

    fun getComponentProductMapping(): Map<String, ProductTypes>
}
