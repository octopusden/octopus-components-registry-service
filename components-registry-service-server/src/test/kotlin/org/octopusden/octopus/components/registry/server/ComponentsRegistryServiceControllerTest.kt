package org.octopusden.octopus.components.registry.server

import com.fasterxml.jackson.core.type.TypeReference
import java.net.URI
import java.util.Date
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.components.registry.core.dto.ComponentV2
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersions
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.DocDTO
import org.octopusden.octopus.components.registry.core.dto.EscrowDTO
import org.octopusden.octopus.components.registry.core.dto.EscrowGenerationMode
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.SecurityGroupsDTO
import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import org.octopusden.octopus.components.registry.core.dto.VersionNamesDTO
import org.octopusden.octopus.components.registry.core.dto.VersionRequest
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
@ExtendWith(SpringExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common","test")
class ComponentsRegistryServiceControllerTest : BaseComponentsRegistryServiceTest() {

    private val docker = "test/versions-api"
    private val gav = "org.octopusden.octopus.test:versions-api:jar"

    @Autowired
    private lateinit var mvc: MockMvc

    override fun getAllJiraComponentVersionRanges(): Collection<JiraComponentVersionRangeDTO> = mvc.perform(
        MockMvcRequestBuilders.get(URI.create("/rest/api/2/common/jira-component-version-ranges"))
            .accept(APPLICATION_JSON)
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(object : TypeReference<Set<JiraComponentVersionRangeDTO>>() {})
        .sortedBy { it.componentName + it.versionRange }

    override fun getSupportedGroupIds(): Set<String> =
        mvc.perform(
            MockMvcRequestBuilders.get(URI.create("/rest/api/2/common/supported-groups"))
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Set<String>>() {})

    override fun getVersionNames(): VersionNamesDTO = mvc.perform(
        MockMvcRequestBuilders.get(URI.create("/rest/api/2/common/version-names"))
            .accept(APPLICATION_JSON)
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(VersionNamesDTO::class.java)

    override fun getDependencyAliasToComponentMapping() =
        mvc.perform(
            MockMvcRequestBuilders.get(URI.create("/rest/api/2/common/dependency-aliases"))
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Map<String, String>>() {})

    override fun getComponentProductMapping(): Map<String, ProductTypes> =
        mvc.perform(
            MockMvcRequestBuilders.get(URI.create("/rest/api/2/common/component-product-mapping"))
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Map<String, ProductTypes>>() {})

    override fun getComponentV1(component: String): ComponentV1 = mvc.perform(
        MockMvcRequestBuilders.get("/rest/api/1/components/$component")
            .accept(APPLICATION_JSON)
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(ComponentV1::class.java)

    override fun getDetailedComponent(component: String, version: String): DetailedComponent =
        mvc.perform(
            MockMvcRequestBuilders.get(
                "/rest/api/2/components/{component}/versions/{version}",
                component,
                version
            )
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(DetailedComponent::class.java)

    override fun getDetailedComponentVersion(component: String, version: String): DetailedComponentVersion =
        mvc.perform(
            MockMvcRequestBuilders.get(
                "/rest/api/2/components/{component}/versions/{version}/detailed-version",
                component,
                version
            )
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(DetailedComponentVersion::class.java)

    override fun getDetailedComponentVersions(component: String, versions: List<String>): DetailedComponentVersions =
        mvc.perform(
            MockMvcRequestBuilders.post("/rest/api/2/components/{component}/detailed-versions", component)
                .accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(VersionRequest(versions)))
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(DetailedComponentVersions::class.java)

    override fun getVcsSettings(component: String, version: String) = mvc.perform(
        MockMvcRequestBuilders.get(
            "/rest/api/2/components/{component}/versions/{version}/vcs-settings",
            component,
            version
        )
            .accept(APPLICATION_JSON)
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(VCSSettingsDTO::class.java)

    override fun getDistribution(component: String, version: String): DistributionDTO = mvc.perform(
        MockMvcRequestBuilders.get(
            "/rest/api/2/components/{component}/versions/{version}/distribution",
            component,
            version
        )
            .accept(APPLICATION_JSON)
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(DistributionDTO::class.java)

    override fun getBuildTools(component: String, version: String): List<BuildTool> =
        mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/components/{component}/versions/{version}/build-tools", component, version)
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object: TypeReference<List<BuildTool>>() {})

    override fun getJiraComponentVersion(component: String, version: String): JiraComponentVersionDTO =
        mvc.perform(
            MockMvcRequestBuilders.get(
                "/rest/api/2/components/{component}/versions/{version}/jira-component", component, version
            ).accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(JiraComponentVersionDTO::class.java)

    override fun getJiraComponentByProjectAndVersion(component: String, version: String): JiraComponentVersionDTO =
        mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/projects/{projectKey}/versions/{version}", component, version)
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(JiraComponentVersionDTO::class.java)

    override fun getJiraComponentsByProject(projectKey: String): Set<String> = mvc.perform(
        MockMvcRequestBuilders.get("/rest/api/2/projects/{projectKey}/jira-components", "SUB")
            .accept(APPLICATION_JSON)
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(object : TypeReference<Set<String>>() {})

    override fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRangeDTO> =
        mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/projects/{projectKey}/jira-component-version-ranges", projectKey)
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Set<JiraComponentVersionRangeDTO>>() {})

    override fun getComponentsDistributionsByJiraProject(projectKey: String): Map<String, DistributionDTO> =
        mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/projects/{projectKey}/component-distributions", projectKey)
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Map<String, DistributionDTO>>() {})

    override fun getVCSSettingForProject(projectKey: String, version: String): VCSSettingsDTO = mvc.perform(
        MockMvcRequestBuilders.get(
            "/rest/api/2/projects/{projectKey}/versions/{version}/vcs-settings",
            projectKey,
            version
        )
            .accept(APPLICATION_JSON)
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(VCSSettingsDTO::class.java)

    override fun getDistributionForProject(projectKey: String, version: String): DistributionDTO = mvc.perform(
        MockMvcRequestBuilders.get(
            "/rest/api/2/projects/{projectKey}/versions/{version}/distribution",
            projectKey,
            version
        )
            .accept(APPLICATION_JSON)
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(DistributionDTO::class.java)

    override fun getServiceStatus(): ServiceStatusDTO = mvc.perform(
        MockMvcRequestBuilders.get("/rest/api/2/components-registry/service/status").accept(APPLICATION_JSON)
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(ServiceStatusDTO::class.java)

    override fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent = mvc.perform(
        MockMvcRequestBuilders.post("/rest/api/2/components/find-by-artifact")
            .accept(APPLICATION_JSON)
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(artifact))
    )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .toObject(VersionedComponent::class.java)

    override fun findByArtifactsV3(artifacts: Set<ArtifactDependency>) =
        mvc.perform(
            MockMvcRequestBuilders.post("/rest/api/3/components/find-by-artifacts")
                .accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(artifacts))
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(ArtifactComponentsDTO::class.java)

    override fun getComponentArtifactsParameters(component: String): Map<String, ComponentArtifactConfigurationDTO> =
        mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/components/{component}/maven-artifacts", component)
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(object : TypeReference<Map<String, ComponentArtifactConfigurationDTO>>() {})

    @Test
    fun testPing() {
        val response = mvc.perform(MockMvcRequestBuilders.get("/rest/api/2/components-registry/service/ping"))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
        Assertions.assertEquals("Pong", response)
    }

    @Test
    fun testUpdateCacheStatus() {
        val statusOnStart = getServiceStatus()

        val timeBeforeUpdate = Date()

        Assertions.assertTrue(timeBeforeUpdate.after(statusOnStart.cacheUpdatedAt))

        mvc.perform(MockMvcRequestBuilders.put("/rest/api/2/components-registry/service/updateCache"))
            .andExpect(status().isOk)

        val statusOnUpdateCacheStatusDTO = getServiceStatus()
        Assertions.assertTrue(timeBeforeUpdate.before(statusOnUpdateCacheStatusDTO.cacheUpdatedAt))
    }

    @Test
    fun testGetComponents() {
        val components = mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/1/components")
                .param("expand", "true")
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(Components::class.java)

        val expectedComponent = ComponentV1("TESTONE", "Test ONE display name", "adzuba")
        expectedComponent.distribution = DistributionDTO(
            false,
            false,
            gav,
            null,
            null,
            SecurityGroupsDTO(
                listOf("vfiler1-default#group")
            ),
            docker
        )
        expectedComponent.escrow = EscrowDTO(
            buildTask = "clean build -x test",
            providedDependencies = listOf("test:test:1.1"),
            additionalSources = listOf(
                "spa/.gradle",
                "spa/node_modules"
            ),
            isReusable = false,
            generation = EscrowGenerationMode.UNSUPPORTED
        )
        expectedComponent.releaseManager = "user"
        expectedComponent.securityChampion = "user"
        expectedComponent.system = setOf("ALFA", "CLASSIC")
        expectedComponent.clientCode = "CLIENT_CODE"
        expectedComponent.releasesInDefaultBranch = false
        expectedComponent.solution = true

        Assertions.assertEquals(54, components.components.size)
        Assertions.assertTrue(expectedComponent in components.components) {
            components.components.toString()
        }
    }

    @Test
    fun testGetComponentsFilteredByVcsPath() {
        getAndCheckComponents(
            "ssh://hg@mercurial/technical",
            null,
            setOf("TECHNICAL_COMPONENT", "SUB_COMPONENT_ONE", "SUB_COMPONENT_TWO")
        )
        getAndCheckComponents(
            "ssh://hg@mercurial/technical",
            BuildSystem.MAVEN,
            setOf("SUB_COMPONENT_TWO")
        )
        getAndCheckComponents(
            null,
            BuildSystem.MAVEN,
            setOf("SUB_COMPONENT_TWO", "TEST_COMPONENT", "TEST-VERSION")
        )
    }

    @Test
    fun testGetNonExistedComponent() {
        val exception = Assertions.assertThrows(AssertionError::class.java) {
            getDetailedComponent("NOTEXIST", "1")
        }
        Assertions.assertEquals("Status expected:<200> but was:<404>", exception.message)
    }

    @Test
    fun testGetComponentV2() {
        val actualComponent = mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/components/TESTONE")
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TESTONE", "Test ONE display name", "adzuba")
        expectedComponent.distribution = DistributionDTO(
            false,
            false,
            gav,
            null,
            null,
            SecurityGroupsDTO(listOf("vfiler1-default#group")),
            docker,
        )
        expectedComponent.escrow = EscrowDTO(
            buildTask = "clean build -x test",
            providedDependencies = listOf("test:test:1.1"),
            additionalSources = listOf(
                "spa/.gradle",
                "spa/node_modules"
            ),
            isReusable = false,
            generation = EscrowGenerationMode.UNSUPPORTED
        )

        expectedComponent.releaseManager = "user"
        expectedComponent.securityChampion = "user"
        expectedComponent.system = setOf("ALFA", "CLASSIC")
        expectedComponent.clientCode = "CLIENT_CODE"
        expectedComponent.releasesInDefaultBranch = false
        expectedComponent.solution = true

        Assertions.assertEquals(expectedComponent.escrow, actualComponent.escrow, "Escrow do not match")
        Assertions.assertEquals(expectedComponent, actualComponent)
    }

    /**
     * This test is similar to [testGetComponentV2] and exists to ensure that escrow block is correctly
     * deserialized from the response.
     */
    @Test
    fun testGetComponentV2WithEscrowBlock() {
        val actualComponent = mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/components/TEST_COMPONENT_WITH_ESCROW")
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_ESCROW", null, "user9")
        expectedComponent.escrow = EscrowDTO(
            buildTask = null,
            providedDependencies = listOf(),
            additionalSources = listOf(),
            isReusable = true,
            generation = EscrowGenerationMode.MANUAL
        )

        Assertions.assertEquals(expectedComponent.escrow, actualComponent.escrow, "Escrow do not match")
    }

    @Test
    fun testGetComponentDoc() {
        val actualComponent = mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/components/TEST_COMPONENT_WITH_DOC")
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_DOC", "Test Component with Doc", "user9")
        expectedComponent.doc = DocDTO(
            "TEST_COMPONENT_DOC",
            "1.2"
        )
        Assertions.assertEquals(
            expectedComponent.doc,
            actualComponent.doc,
            "Components do not match"
        )
    }

    @Test
    fun testGetComponentVersionEscrow() {
        val actualComponent = mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/components/TEST_COMPONENT_WITH_ESCROW/versions/1.0.0")
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_ESCROW", null, "user9")
        expectedComponent.escrow = EscrowDTO(
            buildTask = null,
            providedDependencies = listOf(),
            additionalSources = listOf(),
            isReusable = true,
            generation = EscrowGenerationMode.MANUAL
        )

        Assertions.assertEquals(
            expectedComponent.escrow,
            actualComponent.escrow,
            "Escrow do not match"
        )
    }

    @Test
    fun testGetComponentVersionDoc() {
        val actualComponent = mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/2/components/TEST_COMPONENT_WITH_DOC/versions/1.0.0")
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_DOC", "Test Component with Doc", "user9")
        expectedComponent.doc = DocDTO(
            "TEST_COMPONENT_DOC",
            "1.2"
        )
        Assertions.assertEquals(
            expectedComponent.doc,
            actualComponent.doc,
            "Components do not match"
        )
    }

    private fun getAndCheckComponents(vcsPath: String?, buildSystem: BuildSystem?, expectedComponents: Set<String>) {
        val components = mvc.perform(
            MockMvcRequestBuilders.get("/rest/api/1/components")
                .param("vcs-path", vcsPath)
                .param("build-system", buildSystem?.name)
                .accept(APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .toObject(Components::class.java)
        Assertions.assertEquals(
            expectedComponents,
            components.components.map { it.name }.toSet()
        )
    }

    private fun <T> MockHttpServletResponse.toObject(javaClass: Class<T>): T {
        return objectMapper.readValue(this.contentAsString, javaClass)
    }

    private fun <T> MockHttpServletResponse.toObject(typeReference: TypeReference<T>): T {
        return objectMapper.readValue(this.contentAsString, typeReference)
    }
}
