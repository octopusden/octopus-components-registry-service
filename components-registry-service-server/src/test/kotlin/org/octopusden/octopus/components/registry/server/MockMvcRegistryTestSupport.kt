package org.octopusden.octopus.components.registry.server

import com.fasterxml.jackson.core.type.TypeReference
import org.junit.jupiter.api.Assertions
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
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
import org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI

abstract class MockMvcRegistryTestSupport : BaseComponentsRegistryServiceTest() {
    @Autowired
    protected lateinit var mvc: MockMvc

    override fun getAllJiraComponentVersionRanges(): Collection<JiraComponentVersionRangeDTO> =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(URI.create("/rest/api/2/common/jira-component-version-ranges"))
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Set<JiraComponentVersionRangeDTO>>() {})
            .sortedBy { it.componentName + it.versionRange }

    override fun getSupportedGroupIds(): Set<String> =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(URI.create("/rest/api/2/common/supported-groups"))
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Set<String>>() {})

    override fun getVersionNames(): VersionNamesDTO =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(URI.create("/rest/api/2/common/version-names"))
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(VersionNamesDTO::class.java)

    override fun getDependencyAliasToComponentMapping() =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(URI.create("/rest/api/2/common/dependency-aliases"))
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Map<String, String>>() {})

    override fun getComponentProductMapping(): Map<String, ProductTypes> =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(URI.create("/rest/api/2/common/component-product-mapping"))
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Map<String, ProductTypes>>() {})

    override fun getComponentV1(component: String): ComponentV1 =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/rest/api/1/components/$component")
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(ComponentV1::class.java)

    override fun getDetailedComponent(
        component: String,
        version: String,
    ): DetailedComponent =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(
                        "/rest/api/2/components/{component}/versions/{version}",
                        component,
                        version,
                    ).accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(DetailedComponent::class.java)

    override fun getDetailedComponentVersion(
        component: String,
        version: String,
    ): DetailedComponentVersion =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(
                        "/rest/api/2/components/{component}/versions/{version}/detailed-version",
                        component,
                        version,
                    ).accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(DetailedComponentVersion::class.java)

    override fun getDetailedComponentVersions(
        component: String,
        versions: List<String>,
    ): DetailedComponentVersions =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/rest/api/2/components/{component}/detailed-versions", component)
                    .accept(APPLICATION_JSON)
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(VersionRequest(versions))),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(DetailedComponentVersions::class.java)

    override fun getVcsSettings(
        component: String,
        version: String,
    ) = mvc
        .perform(
            MockMvcRequestBuilders
                .get(
                    "/rest/api/2/components/{component}/versions/{version}/vcs-settings",
                    component,
                    version,
                ).accept(APPLICATION_JSON),
        ).andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(VCSSettingsDTO::class.java)

    override fun getDistribution(
        component: String,
        version: String,
    ): DistributionDTO =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(
                        "/rest/api/2/components/{component}/versions/{version}/distribution",
                        component,
                        version,
                    ).accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(DistributionDTO::class.java)

    override fun getBuildTools(
        component: String,
        version: String,
    ): List<BuildTool> =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/rest/api/2/components/{component}/versions/{version}/build-tools", component, version)
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<List<BuildTool>>() {})

    override fun getJiraComponentVersion(
        component: String,
        version: String,
    ): JiraComponentVersionDTO =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(
                        "/rest/api/2/components/{component}/versions/{version}/jira-component",
                        component,
                        version,
                    ).accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(JiraComponentVersionDTO::class.java)

    override fun getJiraComponentByProjectAndVersion(
        component: String,
        version: String,
    ): JiraComponentVersionDTO =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/rest/api/2/projects/{projectKey}/versions/{version}", component, version)
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(JiraComponentVersionDTO::class.java)

    override fun getJiraComponentsByProject(projectKey: String): Set<String> =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/rest/api/2/projects/{projectKey}/jira-components", "SUB")
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Set<String>>() {})

    override fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRangeDTO> =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/rest/api/2/projects/{projectKey}/jira-component-version-ranges", projectKey)
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Set<JiraComponentVersionRangeDTO>>() {})

    override fun getComponentsDistributionsByJiraProject(projectKey: String): Map<String, DistributionDTO> =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/rest/api/2/projects/{projectKey}/component-distributions", projectKey)
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Map<String, DistributionDTO>>() {})

    override fun getVCSSettingForProject(
        projectKey: String,
        version: String,
    ): VCSSettingsDTO =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(
                        "/rest/api/2/projects/{projectKey}/versions/{version}/vcs-settings",
                        projectKey,
                        version,
                    ).accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(VCSSettingsDTO::class.java)

    override fun getDistributionForProject(
        projectKey: String,
        version: String,
    ): DistributionDTO =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get(
                        "/rest/api/2/projects/{projectKey}/versions/{version}/distribution",
                        projectKey,
                        version,
                    ).accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(DistributionDTO::class.java)

    override fun getServiceStatus(): ServiceStatusDTO =
        mvc
            .perform(
                MockMvcRequestBuilders.get("/rest/api/2/components-registry/service/status").accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(ServiceStatusDTO::class.java)

    override fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/rest/api/2/components/find-by-artifact")
                    .accept(APPLICATION_JSON)
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(artifact)),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(VersionedComponent::class.java)

    override fun findByArtifactsV3(artifacts: Set<ArtifactDependency>) =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/rest/api/3/components/find-by-artifacts")
                    .accept(APPLICATION_JSON)
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(artifacts)),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(ArtifactComponentsDTO::class.java)

    override fun getComponentArtifactsParameters(component: String): Map<String, ComponentArtifactConfigurationDTO> =
        mvc
            .perform(
                MockMvcRequestBuilders
                    .get("/rest/api/2/components/{component}/maven-artifacts", component)
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Map<String, ComponentArtifactConfigurationDTO>>() {})

    protected fun getAndCheckComponents(
        vcsPath: String?,
        buildSystem: BuildSystem?,
        expectedComponents: Set<String>,
    ) {
        val components =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/api/1/components")
                        .param("vcs-path", vcsPath)
                        .param("build-system", buildSystem?.name)
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .toObject(Components::class.java)
        Assertions.assertEquals(
            expectedComponents,
            components.components.map { it.name }.toSet(),
        )
    }

    protected fun <T> MockHttpServletResponse.toObject(javaClass: Class<T>): T = objectMapper.readValue(this.contentAsString, javaClass)

    protected fun <T> MockHttpServletResponse.toObject(typeReference: TypeReference<T>): T =
        objectMapper.readValue(this.contentAsString, typeReference)
}
