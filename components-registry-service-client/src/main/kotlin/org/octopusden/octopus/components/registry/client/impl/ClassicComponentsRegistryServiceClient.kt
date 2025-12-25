package org.octopusden.octopus.components.registry.client.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import feign.Feign
import feign.Logger
import feign.Param
import feign.Response
import feign.httpclient.ApacheHttpClient
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import feign.slf4j.Slf4jLogger
import org.octopusden.octopus.components.registry.api.VersionedComponentConfiguration
import org.octopusden.octopus.components.registry.api.beans.VersionedComponentConfigurationBean
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceErrorDecoder
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentImage
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.core.dto.VersionNamesDTO
import org.octopusden.octopus.components.registry.core.dto.VersionRequest
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent

class ClassicComponentsRegistryServiceClient(
    apiUrlProvider: ClassicComponentsRegistryServiceClientUrlProvider,
    private val objectMapper: ObjectMapper
) : ComponentsRegistryServiceClient {

    constructor(apiUrlProvider: ClassicComponentsRegistryServiceClientUrlProvider) : this(
        apiUrlProvider,
        defaultObjectMapper
    )

    private var client = createClient(apiUrlProvider.getApiUrl(), objectMapper)

    override fun getComponents() = client.getComponents()

    override fun findArtifactComponentsByArtifacts(artifacts: Set<ArtifactDependency>) =
        client.findArtifactComponentsByArtifacts(artifacts)

    override fun findComponentsByDockerImages(images: Set<Image>): Set<ComponentImage> =
        client.findComponentsByDockerImages(images)

    override fun getById(componentKey: String) = client.getById(componentKey)

    override fun getDetailedComponent(componentKey: String, version: String) =
        client.getDetailedComponent(componentKey, version)

    override fun getAllComponents(
        vcsPath: String?,
        buildSystem: BuildSystem?,
        solution: Boolean?,
        systems: List<String>
    ) = client.getAllComponents(vcsPath, buildSystem, solution, systems)

    override fun getComponentDistribution(componentKey: String, version: String) =
        client.getComponentDistribution(componentKey, version)

    override fun getDetailedComponentVersion(componentKey: String, version: String) =
        client.getDetailedComponentVersion(componentKey, version)

    override fun getDetailedComponentVersions(componentKey: String, versions: VersionRequest) =
        client.getDetailedComponentVersions(componentKey, versions)

    override fun getComponentArtifactsParameters(componentKey: String): Map<String, ComponentArtifactConfigurationDTO> =
        client.getComponentArtifactsParameters(componentKey)

    override fun getVCSSetting(componentKey: String, version: String) = client.getVCSSetting(componentKey, version)

    override fun getBuildTools(
        @Param(value = "componentKey") componentKey: String,
        @Param(value = "version") version: String,
        @Param(value = "ignoreRequired") ignoreRequired: Boolean?
    ): List<BuildTool> =
        client.getBuildTools(componentKey, version, ignoreRequired)

    override fun getJiraComponentForComponentAndVersion(
        componentKey: String,
        version: String
    ): JiraComponentVersionDTO =
        client.getJiraComponentForComponentAndVersion(componentKey, version)

    override fun getJiraComponentByProjectAndVersion(projectKey: String, version: String) =
        client.getJiraComponentByProjectAndVersion(projectKey, version)

    override fun getJiraComponentsByProject(projectKey: String): Set<String> =
        client.getJiraComponentsByProject(projectKey)

    override fun getJiraComponentVersionRangesByProject(projectKey: String) =
        client.getJiraComponentVersionRangesByProject(projectKey)

    override fun getComponentsDistributionByJiraProject(projectKey: String) =
        client.getComponentsDistributionByJiraProject(projectKey)

    override fun getVCSSettingForProject(projectKey: String, version: String) =
        client.getVCSSettingForProject(projectKey, version)

    override fun getDistributionForProject(projectKey: String, version: String): DistributionDTO =
        client.getDistributionForProject(projectKey, version)

    override fun getAllJiraComponentVersionRanges(): Set<JiraComponentVersionRangeDTO> =
        client.getAllJiraComponentVersionRanges()

    override fun getDependencyAliasToComponentMapping(): Map<String, String> =
        client.getDependencyAliasToComponentMapping()

    override fun getSupportedGroupIds(): Set<String> =
        client.getSupportedGroupIds()

    override fun getComponentProductMapping(): Map<String, ProductTypes> =
        client.getComponentProductMapping()

    override fun getVersionNames(): VersionNamesDTO =
        client.getVersionNames()

    override fun getServiceStatus(): ServiceStatusDTO =
        client.getServiceStatus()

    override fun getCopyrightByComponent(componentKey: String): Response =
        client.getCopyrightByComponent(componentKey)

    override fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent =
        client.findComponentByArtifact(artifact)

    override fun findComponentsByArtifacts(artifacts: Collection<ArtifactDependency>): Collection<VersionedComponent> =
        client.findComponentsByArtifacts(artifacts)

    fun setUrl(apiUrl: String) {
        client = createClient(apiUrl, objectMapper)
    }

    private fun createClient(apiUrl: String, objectMapper: ObjectMapper): ComponentsRegistryServiceClient {
        return Feign.builder()
            .client(ApacheHttpClient())
            .encoder(JacksonEncoder(objectMapper))
            .decoder(JacksonDecoder(objectMapper))
            .errorDecoder(ComponentsRegistryServiceErrorDecoder(objectMapper))
            .encoder(JacksonEncoder())
            .decoder(JacksonDecoder())
            .logger(Slf4jLogger(ComponentsRegistryServiceClient::class.java))
            .logLevel(Logger.Level.FULL)
            .target(ComponentsRegistryServiceClient::class.java, apiUrl)
    }

    companion object {
        private val defaultObjectMapper: ObjectMapper = configureObjectMapper(ObjectMapper())

        /**
         * Configure given [objectMapper] and return it back.
         * Deserialization can require mapping interfaces to classes.
         * To avoid manually type mapping configuration use this method to configure [objectMapper].
         * For example: configure autowired Spring object mapper in service
         */
        @JvmStatic
        fun configureObjectMapper(objectMapper: ObjectMapper): ObjectMapper {
            val module = SimpleModule()
            module.addAbstractTypeMapping(
                VersionedComponentConfiguration::class.java,
                VersionedComponentConfigurationBean::class.java
            )
            objectMapper.registerModule(module)
            return objectMapper
        }
    }
}
