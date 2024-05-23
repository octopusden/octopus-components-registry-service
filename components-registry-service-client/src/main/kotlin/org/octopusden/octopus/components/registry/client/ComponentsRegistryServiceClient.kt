package org.octopusden.octopus.components.registry.client

import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.components.registry.core.dto.ComponentV2
import org.octopusden.octopus.components.registry.core.dto.ComponentV3
import org.octopusden.octopus.components.registry.core.dto.ComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersions
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import org.octopusden.octopus.components.registry.core.dto.VersionNamesDTO
import org.octopusden.octopus.components.registry.core.dto.VersionRequest
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import feign.CollectionFormat
import feign.Headers
import feign.Param
import feign.QueryMap
import feign.RequestLine

interface ComponentsRegistryServiceClient {
    /**
     * Get all components.
     */
    @RequestLine("GET /rest/api/3/components")
    fun getComponents(): Collection<ComponentV3>

    @RequestLine("POST rest/api/3/components/find-by-artifacts")
    @Headers("Content-Type: application/json")
    fun findArtifactComponentsByArtifacts(artifacts: Set<ArtifactDependency>): ArtifactComponentsDTO

    @RequestLine("GET /rest/api/1/components/{componentKey}")
    @Throws(NotFoundException::class)
    fun getById(@Param("componentKey") componentKey: String): ComponentV1

    @RequestLine("GET rest/api/2/components/{componentKey}/versions/{version}")
    @Throws(NotFoundException::class)
    fun getComponent(@Param("componentKey") componentKey: String, @Param("version") version: String): ComponentV2

    @RequestLine("GET /rest/api/2/components?vcs-path={vcsPath}&build-system={buildSystem}&systems={systems}", collectionFormat = CollectionFormat.CSV)
    fun getAllComponents(
        @Param("vcsPath") vcsPath: String? = null,
        @Param("buildSystem") buildSystem: BuildSystem? = null,
        @Param("systems") @QueryMap systems: List<String> = emptyList()
    ): ComponentsDTO<ComponentV2>

    @RequestLine("GET rest/api/1/components/{componentKey}/versions/{version}/distribution")
    @Throws(NotFoundException::class)
    fun getComponentDistribution(@Param("componentKey") componentKey: String, @Param("version") version: String): DistributionDTO

    @RequestLine("GET rest/api/2/components/{componentKey}/versions/{version}/detailed-version")
    @Throws(NotFoundException::class)
    fun getDetailedComponentVersion(@Param("componentKey") componentKey: String, @Param("version") version: String): DetailedComponentVersion

    @RequestLine("POST rest/api/2/components/{componentKey}/detailed-versions")
    @Headers("Content-Type: application/json")
    fun getDetailedComponentVersions(@Param("componentKey") componentKey: String, versions: VersionRequest): DetailedComponentVersions

    @RequestLine("GET rest/api/2/components/{componentKey}/maven-artifacts")
    fun getComponentArtifactsParameters(@Param("componentKey") componentKey: String): Map<String, ComponentArtifactConfigurationDTO>

    @RequestLine("GET rest/api/2/components/{componentKey}/versions/{version}/vcs-settings")
    @Throws(NotFoundException::class)
    fun getVCSSetting(@Param("componentKey") componentKey: String, @Param("version") version: String): VCSSettingsDTO

    @RequestLine("GET rest/api/2/components/{componentKey}/versions/{version}/jira-component")
    fun getJiraComponentForComponentAndVersion(@Param("componentKey") componentKey: String, @Param("version") version: String): JiraComponentVersionDTO

    @RequestLine("GET rest/api/2/projects/{projectKey}/versions/{version}")
    fun getJiraComponentByProjectAndVersion(@Param("projectKey") projectKey: String, @Param("version") version: String): JiraComponentVersionDTO

    @RequestLine("GET rest/api/2/projects/{projectKey}/jira-components")
    fun getJiraComponentsByProject(@Param("projectKey") projectKey: String): Set<String>

    @RequestLine("GET rest/api/2/projects/{projectKey}/jira-component-version-ranges")
    fun getJiraComponentVersionRangesByProject(@Param("projectKey") projectKey: String): Set<JiraComponentVersionRangeDTO>

    @RequestLine("GET rest/api/2/projects/{projectKey}/component-distributions")
    fun getComponentsDistributionByJiraProject(@Param("projectKey") projectKey: String): Map<String, DistributionDTO>

    @RequestLine("GET rest/api/2/projects/{projectKey}/versions/{version}/vcs-settings")
    fun getVCSSettingForProject(@Param("projectKey") projectKey: String, @Param("version") version: String): VCSSettingsDTO

    @RequestLine("GET rest/api/2/projects/{projectKey}/versions/{version}/distribution")
    fun getDistributionForProject(@Param("projectKey") projectKey: String, @Param("version") version: String): DistributionDTO

    @RequestLine("GET rest/api/2/common/jira-component-version-ranges")
    fun getAllJiraComponentVersionRanges(): Set<JiraComponentVersionRangeDTO>

    @RequestLine("GET rest/api/2/common/dependency-aliases")
    fun getDependencyAliasToComponentMapping(): Map<String, String>

    @RequestLine("GET rest/api/2/common/supported-groups")
    fun getSupportedGroupIds(): Set<String>

    @RequestLine("GET rest/api/2/common/version-names")
    fun getVersionNames(): VersionNamesDTO

    @RequestLine("GET rest/api/2/components-registry/service/status")
    fun getServiceStatus(): ServiceStatusDTO

    @RequestLine("POST rest/api/2/components/find-by-artifact")
    @Headers("Content-Type: application/json")
    @Throws(NotFoundException::class)
    fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent

    @RequestLine("POST rest/api/2/components/findByArtifacts")
    @Headers("Content-Type: application/json")
    fun findComponentsByArtifacts(artifacts: Collection<ArtifactDependency>): Collection<VersionedComponent>
}
