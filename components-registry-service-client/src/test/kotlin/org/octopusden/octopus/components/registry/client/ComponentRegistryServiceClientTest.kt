package org.octopusden.octopus.components.registry.client

import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersions
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import org.octopusden.octopus.components.registry.core.dto.VersionRequest
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.ResourceLock
import org.octopusden.octopus.components.registry.core.dto.VersionNamesDTO
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.Date
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(SpringExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test")
@ResourceLock(value = "SYSTEM_PROPERTIES")
class ComponentRegistryServiceClientTest : BaseComponentsRegistryServiceTest() {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var componentsRegistryClient: ComponentsRegistryServiceClient
    private val testStartDate: Date = Date()

    @BeforeAll
    internal fun startupServer() {
        componentsRegistryClient = ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String {
                    return "http://localhost:$port"
                }
            }
        )
    }

    override fun getAllJiraComponentVersionRanges(): Collection<JiraComponentVersionRangeDTO> =
        componentsRegistryClient.getAllJiraComponentVersionRanges()

    override fun getComponentV1(component: String): ComponentV1 = componentsRegistryClient.getById(component)

    override fun getDetailedComponentVersion(component: String, version: String): DetailedComponentVersion =
        componentsRegistryClient.getDetailedComponentVersion(component, version)

    override fun getDetailedComponentVersions(component: String, versions: List<String>): DetailedComponentVersions =
        componentsRegistryClient.getDetailedComponentVersions(component, VersionRequest(versions))

    override fun getVcsSettings(component: String, version: String): VCSSettingsDTO =
        componentsRegistryClient.getVCSSetting(component, version)

    override fun getDistribution(component: String, version: String): DistributionDTO =
        componentsRegistryClient.getComponentDistribution(component, version)

    override fun getJiraComponentVersion(component: String, version: String): JiraComponentVersionDTO =
        componentsRegistryClient.getJiraComponentForComponentAndVersion(component, version)

    override fun getJiraComponentByProjectAndVersion(component: String, version: String): JiraComponentVersionDTO =
        componentsRegistryClient.getJiraComponentByProjectAndVersion(component, version)

    override fun getJiraComponentsByProject(projectKey: String): Set<String> =
        componentsRegistryClient.getJiraComponentsByProject(projectKey)

    override fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRangeDTO> =
        componentsRegistryClient.getJiraComponentVersionRangesByProject(projectKey)

    override fun getComponentsDistributionsByJiraProject(projectKey: String): Map<String, DistributionDTO> =
        componentsRegistryClient.getComponentsDistributionByJiraProject(projectKey)

    override fun getVCSSettingForProject(projectKey: String, version: String): VCSSettingsDTO =
        componentsRegistryClient.getVCSSettingForProject(projectKey, version)

    override fun getDistributionForProject(projectKey: String, version: String): DistributionDTO =
        componentsRegistryClient.getDistributionForProject(projectKey, version)

    override fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent =
        componentsRegistryClient.findComponentByArtifact(artifact)

    override fun findByArtifactsV3(artifacts: Set<ArtifactDependency>) =
        componentsRegistryClient.findArtifactComponentsByArtifacts(artifacts)

    override fun getComponentArtifactsParameters(component: String): Map<String, ComponentArtifactConfigurationDTO> =
        componentsRegistryClient.getComponentArtifactsParameters(component)

    override fun getSupportedGroupIds(): Set<String> = componentsRegistryClient.getSupportedGroupIds()

    override fun getVersionNames(): VersionNamesDTO = componentsRegistryClient.getVersionNames()

    override fun getDependencyAliasToComponentMapping(): Map<String, String> =
        componentsRegistryClient.getDependencyAliasToComponentMapping()

    override fun getServiceStatus(): ServiceStatusDTO = componentsRegistryClient.getServiceStatus()

    @Test
    fun testGetAllComponents() {
        assertEquals(37, componentsRegistryClient.getAllComponents().components.size)
        assertEquals(
            3,
            componentsRegistryClient.getAllComponents("ssh://hg@mercurial/technical", null).components.size
        )
        assertEquals(2, componentsRegistryClient.getAllComponents(null, BuildSystem.MAVEN).components.size)
        assertEquals(
            1,
            componentsRegistryClient.getAllComponents(
                "ssh://hg@mercurial/technical",
                BuildSystem.MAVEN
            ).components.size
        )
        assertEquals(4, componentsRegistryClient.getAllComponents(systems = listOf("CLASSIC")).components.size)
        assertEquals(33, componentsRegistryClient.getAllComponents(systems = listOf("NONE")).components.size)
        assertEquals(37, componentsRegistryClient.getAllComponents(systems = listOf("CLASSIC", "NONE")).components.size)
    }

    @Test
    fun testGetNonExistedComponent() {
        assertFailsWith(NotFoundException::class) {
            componentsRegistryClient.getById("TESTONE-?")
        }
    }

    @Test
    fun testGetServiceStatus() {
        val serviceStatus = getServiceStatus()
        assertTrue(serviceStatus.cacheUpdatedAt.before(Date()))
        assertTrue(serviceStatus.cacheUpdatedAt.after(testStartDate))
    }

    @Test
    fun testFindComponentByArtifacts() {
        val components = componentsRegistryClient.findComponentsByArtifacts(
            listOf(
                ArtifactDependency(
                    "org.octopusden.octopus.sub2",
                    "sub-component2",
                    "0.1"
                )
            )
        )
        assertEquals(1, components.size)
        val versionedComponent = components.first()
        assertEquals("sub-component2", versionedComponent.id)
        assertEquals("0.1", versionedComponent.version)

        assertTrue(
            componentsRegistryClient.findComponentsByArtifacts(listOf(ArtifactDependency("N/A", "N/A", "0.1")))
                .isEmpty()
        )
    }
}
