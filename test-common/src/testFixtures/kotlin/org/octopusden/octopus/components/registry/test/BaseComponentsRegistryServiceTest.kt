package org.octopusden.octopus.components.registry.test

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Arrays
import java.util.stream.Collectors
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.distribution.DistributionEntity
import org.octopusden.octopus.components.registry.api.distribution.entities.MavenArtifactDistributionEntity
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildParametersDTO
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentInfoDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentRegistryVersion
import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.components.registry.core.dto.ComponentVersionFormatDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentVersionType
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersions
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.EscrowDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.RepositoryType
import org.octopusden.octopus.components.registry.core.dto.SecurityGroupsDTO
import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.core.dto.ToolDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import org.octopusden.octopus.components.registry.core.dto.VersionControlSystemRootDTO
import org.octopusden.octopus.components.registry.core.dto.VersionNamesDTO
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent

const val LINE_VERSION = "3"
const val MINOR_VERSION = "3"
const val BUILD_VERSION = "3.0.0"
const val HOTFIX_VERSION = "3.0.0-0"
const val RC_VERSION = "3.0_RC"
const val RELEASE_VERSION = "3.0"
const val VERSION_PREFIX = "sub1k-"
const val JIRA_LINE_VERSION = "$VERSION_PREFIX$LINE_VERSION"
const val JIRA_MINOR_VERSION = "$VERSION_PREFIX$MINOR_VERSION"
const val JIRA_BUILD_VERSION = "$VERSION_PREFIX$BUILD_VERSION"
const val JIRA_RC_VERSION = "$VERSION_PREFIX$RC_VERSION"
const val JIRA_RELEASE_VERSION = "$VERSION_PREFIX$RELEASE_VERSION"
const val JIRA_HOTFIX_VERSION = "$VERSION_PREFIX$HOTFIX_VERSION"

val SUB_COMPONENTS = setOf("SUB", "client", "commoncomponent-test", "SUB_WITH_SIMPLE_VERSION_FORMAT").sorted()

val DETAILED_COMPONENT_VERSION = DetailedComponentVersion(
    component = "PPROJECT WITH CLIENT COMPONENT",
    lineVersion = ComponentRegistryVersion(ComponentVersionType.LINE, LINE_VERSION, JIRA_LINE_VERSION),
    minorVersion = ComponentRegistryVersion(ComponentVersionType.MINOR, MINOR_VERSION, JIRA_MINOR_VERSION),
    buildVersion = ComponentRegistryVersion(ComponentVersionType.BUILD, BUILD_VERSION, JIRA_BUILD_VERSION),
    rcVersion = ComponentRegistryVersion(ComponentVersionType.RC, RC_VERSION, JIRA_RC_VERSION),
    releaseVersion = ComponentRegistryVersion(ComponentVersionType.RELEASE, RELEASE_VERSION, JIRA_RELEASE_VERSION),
    hotfixVersion = ComponentRegistryVersion(ComponentVersionType.HOTFIX, HOTFIX_VERSION, JIRA_HOTFIX_VERSION)
)

val VCS_SETTINGS = VCSSettingsDTO(
    versionControlSystemRoots = listOf(
        VersionControlSystemRootDTO(
            vcsPath = "ssh://hg@mercurial/sub",
            name = "main",
            branch = "v2",
            tag = "SUB-3.0.0",
            type = RepositoryType.MERCURIAL,
            hotfixBranch = null
        )
    )
)

abstract class BaseComponentsRegistryServiceTest {
    init {
        configureSpringAppTestDataDir()
    }

    protected val objectMapper: ObjectMapper = ObjectMapper()
    private val testDataDir = getTestResourcesPath()

    //common
    protected abstract fun getAllJiraComponentVersionRanges(): Collection<JiraComponentVersionRangeDTO>
    protected abstract fun getSupportedGroupIds(): Set<String>
    abstract fun getVersionNames(): VersionNamesDTO
    protected abstract fun getDependencyAliasToComponentMapping(): Map<String, String>
    protected abstract fun getComponentProductMapping(): Map<String, ProductTypes>

    protected abstract fun getComponentV1(component: String): ComponentV1
    protected abstract fun getDetailedComponent(component: String, version: String): DetailedComponent
    protected abstract fun getDetailedComponentVersion(component: String, version: String): DetailedComponentVersion
    protected abstract fun getDetailedComponentVersions(
        component: String,
        versions: List<String>
    ): DetailedComponentVersions

    protected abstract fun getVcsSettings(component: String, version: String): VCSSettingsDTO
    protected abstract fun getDistribution(component: String, version: String): DistributionDTO
    protected abstract fun getBuildTools(component: String, version: String): List<BuildTool>
    protected abstract fun getDistributionEntities(component: String, version: String): List<DistributionEntity>
    protected abstract fun getJiraComponentVersion(component: String, version: String): JiraComponentVersionDTO
    protected abstract fun getJiraComponentByProjectAndVersion(
        component: String,
        version: String
    ): JiraComponentVersionDTO

    protected abstract fun getJiraComponentsByProject(projectKey: String): Set<String>
    protected abstract fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRangeDTO>
    protected abstract fun getComponentsDistributionsByJiraProject(projectKey: String): Map<String, DistributionDTO>
    protected abstract fun getVCSSettingForProject(projectKey: String, version: String): VCSSettingsDTO
    protected abstract fun getDistributionForProject(projectKey: String, version: String): DistributionDTO
    protected abstract fun getServiceStatus(): ServiceStatusDTO

    protected abstract fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent
    //    protected abstract fun findByArtifactsV2(artifacts: Set<ArtifactDependency>): Map<ArtifactDependency, VersionedComponent?>
    protected abstract fun findByArtifactsV3(artifacts: Set<ArtifactDependency>): ArtifactComponentsDTO
    protected abstract fun getComponentArtifactsParameters(component: String): Map<String, ComponentArtifactConfigurationDTO>

    @Test
    fun testGetAllJiraComponentVersionRanges() {
        val expected = testDataDir.resolve("expected-data/jira-component-version-ranges.json")
            .toObject(object : TypeReference<Set<JiraComponentVersionRangeDTO>>() {})
            .sortedBy { it.componentName + it.versionRange }
        val actual = getAllJiraComponentVersionRanges().stream()
            .sorted { o1, o2 -> (o1.componentName + o1.versionRange).compareTo(o2.componentName + o2.versionRange)}
            .collect(Collectors.toList())
        Assertions.assertTrue(expected.containsAll(actual))
        Assertions.assertTrue(actual.containsAll(expected))
        Assertions.assertIterableEquals(expected, actual)
    }

    @Test
    fun testGetSupportedGroupIds() {
        Assertions.assertEquals(Arrays.asList("org.octopusden.octopus", "io.bcomponent").sorted(), getSupportedGroupIds().sorted())
    }

    @Test
    fun testGetComponentProductMapping() {
        Assertions.assertEquals(
            mapOf("TEST_PT_K_DB" to ProductTypes.PT_K),
            getComponentProductMapping()
        )
    }

    @Test
    fun testVersionNames() {
        val value = getVersionNames()
        Assertions.assertEquals("serviceCBranch", value.serviceBranch)
        Assertions.assertEquals("serviceC", value.service)
        Assertions.assertEquals("minorC", value.minor)
    }

    @Test
    fun testGetDependencyAliasToComponentMapping() {
        Assertions.assertEquals(
            mapOf("alias1" to "sub1", "alias2" to "sub2").toSortedMap(),
            getDependencyAliasToComponentMapping().toSortedMap()
        )
    }

    @Test
    fun testGetComponentV1() {
        val actualComponent = getComponentV1("TESTONE")

        val expectedComponent = ComponentV1("TESTONE", "Test ONE display name", "adzuba")
        expectedComponent.distribution = DistributionDTO(
            false,
            false,
            "org.octopusden.octopus.test:versions-api:jar",
            securityGroups = SecurityGroupsDTO(listOf("vfiler1-default#group")),
            docker = "test/versions-api"
        )
        expectedComponent.releaseManager = "user"
        expectedComponent.securityChampion = "user"
        expectedComponent.system = listOf("NONE")
        expectedComponent.clientCode = "CLIENT_CODE"
        expectedComponent.releasesInDefaultBranch = false
        expectedComponent.solution = true
        Assertions.assertEquals(expectedComponent, actualComponent)
    }

    @Test
    fun testGetSubComponentV1() {
        val actualComponent = getComponentV1("versions-api")

        val expectedComponent = ComponentV1("versions-api", "versions-api", "user9")
        expectedComponent.distribution = DistributionDTO(false, true,
            securityGroups = SecurityGroupsDTO(listOf("vfiler1-default#group")))
        expectedComponent.releaseManager = "user"
        expectedComponent.securityChampion = "user"
        expectedComponent.system = listOf("NONE")
        expectedComponent.clientCode = "CLIENT_CODE"
        expectedComponent.releasesInDefaultBranch = false
        expectedComponent.solution = true
        expectedComponent.parentComponent = "TESTONE"
        Assertions.assertEquals(expectedComponent, actualComponent)
    }

    @Test
    fun testGetDetailedComponent() {
        val actualComponent = getDetailedComponent("TESTONE", "1")
        val expectedComponent = DetailedComponent(
            "TESTONE", "Test ONE display name", "adzuba",
            BuildSystem.PROVIDED,
            vcsSettings = VCSSettingsDTO(
                versionControlSystemRoots = listOf(
                    VersionControlSystemRootDTO(
                        name = "main",
                        vcsPath = "ssh://hg@mercurial/test-component",
                        type = RepositoryType.MERCURIAL,
                        tag = "TESTONE-1.0.0",
                        branch = "v2",
                        hotfixBranch = null
                    )
                ),
                externalRegistry = null
            ),
            jiraComponentVersion = JiraComponentVersionDTO(
                name = "TESTONE",
                version = "1",
                component = JiraComponentDTO(
                    projectKey = "TESTONE",
                    displayName = "TESTONE DISPLAY NAME WITH VERSIONS-API",
                    componentVersionFormat = ComponentVersionFormatDTO(
                        majorVersionFormat = "\$major",
                        releaseVersionFormat = "\$major.\$minor",
                        buildVersionFormat = "\$major.\$minor.\$service",
                        lineVersionFormat = "\$major",
                        hotfixVersionFormat = "\$major.\$minor.\$service.\$fix"
                    ),
                    componentInfo = ComponentInfoDTO(
                        versionPrefix = "",
                        versionFormat = "\$versionPrefix-\$baseVersionFormat"
                    ),
                    technical = false
                )
            ),
            detailedComponentVersion = DetailedComponentVersion(
                component = "TESTONE DISPLAY NAME WITH VERSIONS-API",
                minorVersion = ComponentRegistryVersion(ComponentVersionType.MINOR, "1", "1"),
                lineVersion = ComponentRegistryVersion(ComponentVersionType.LINE, "1", "1"),
                releaseVersion = ComponentRegistryVersion(ComponentVersionType.RELEASE, "1.0", "1.0"),
                rcVersion = ComponentRegistryVersion(ComponentVersionType.RC, "1.0_RC", "1.0_RC"),
                buildVersion = ComponentRegistryVersion(ComponentVersionType.BUILD, "1.0.0", "1.0.0"),
                hotfixVersion = ComponentRegistryVersion(ComponentVersionType.HOTFIX, "1.0.0.0", "1.0.0.0")
            ),
            buildFilePath = "build"
        )
        expectedComponent.distribution = DistributionDTO(
            false,
            false,
            "org.octopusden.octopus.test:versions-api:jar",
            securityGroups = SecurityGroupsDTO(listOf("vfiler1-default#group")),
            docker = "test/versions-api:1"
        )
        expectedComponent.releaseManager = "user"
        expectedComponent.securityChampion = "user"
        expectedComponent.system = listOf("NONE")
        expectedComponent.clientCode = "CLIENT_CODE"
        expectedComponent.releasesInDefaultBranch = false
        expectedComponent.buildParameters = BuildParametersDTO(
            javaVersion = "11",
            mavenVersion = "3.6.3",
            gradleVersion = "LATEST",
            requiredProject = false,
            buildTasks = "clean build",
            tools = listOf(
                ToolDTO(
                    name = "BuildEnv",
                    escrowEnvironmentVariable = "BUILD_ENV",
                    sourceLocation = "\$env.BUILD_ENV",
                    targetLocation = "tools/BUILD_ENV"
                ),
                ToolDTO(
                    name = "PowerBuilderCompiler170",
                    escrowEnvironmentVariable = "PBC_BIN",
                    sourceLocation = "\$env.PBC/170",
                    targetLocation = "tools/auto_compiler"
                )
            )
        )
        expectedComponent.escrow = EscrowDTO(
            providedDependencies = listOf("test:test:1.1"),
            diskSpaceRequirement = null,
            additionalSources = listOf(
                "spa/.gradle",
                "spa/node_modules"
            ),
            isReusable = false
        )
        Assertions.assertEquals(expectedComponent, actualComponent)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        BUILD_VERSION, JIRA_BUILD_VERSION, LINE_VERSION, JIRA_LINE_VERSION, MINOR_VERSION, JIRA_MINOR_VERSION, RC_VERSION, JIRA_RC_VERSION, JIRA_RELEASE_VERSION, RELEASE_VERSION, HOTFIX_VERSION])
    fun testGetDetailedComponentVersion(version: String) {
        val actualComponentVersion = getDetailedComponentVersion("SUB", version)
        Assertions.assertEquals(DETAILED_COMPONENT_VERSION, actualComponentVersion)
    }

    @Test
    fun testGetDetailedComponentVersions() {
        val actualComponentVersions = getDetailedComponentVersions("SUB", listOf(BUILD_VERSION))
        Assertions.assertEquals(
            DetailedComponentVersions(mapOf(BUILD_VERSION to DETAILED_COMPONENT_VERSION)),
            actualComponentVersions
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [BUILD_VERSION, JIRA_BUILD_VERSION, LINE_VERSION, JIRA_LINE_VERSION, MINOR_VERSION, JIRA_MINOR_VERSION, RC_VERSION, JIRA_RC_VERSION, JIRA_RELEASE_VERSION, RELEASE_VERSION, HOTFIX_VERSION])
    fun testGetVCSSettings(version: String) {
        Assertions.assertEquals(VCS_SETTINGS, getVcsSettings("SUB", version))
    }

    @Test
    fun testGetDistribution() {
        val actualDistribution = getDistribution("TEST_COMPONENT3", "1.0.108.11")
        Assertions.assertTrue(actualDistribution.explicit)
        Assertions.assertTrue(actualDistribution.external)
        Assertions.assertEquals(SecurityGroupsDTO(listOf("vfiler1#group")), actualDistribution.securityGroups)
        Assertions.assertEquals(
            "org.octopusden.octopus.test:octopusmpi:war,org.octopusden.octopus.test:octopusacs:war,org.octopusden.octopus.test:demo:war,file:///acs:\$major-\$minor-\$service-\$fix",
            actualDistribution.gav,
            "Was returned distribution as is to perform expression evaluation on client side"
        )
    }

    @Test
    fun testGetBuildTools() {
        val buildTools = getBuildTools("TEST_COMPONENT_BUILD_TOOLS", "1.0.0")
        val oracle = OracleDatabaseToolBean()
        oracle.version = "11.2"
        val ptk = PTKProductToolBean()
        ptk.version = "03.49"
        Assertions.assertTrue(buildTools.contains(oracle))
        Assertions.assertTrue(buildTools.contains(ptk))
    }

    @Test
    fun testGetDistributionEntities() {
        val entities = getDistributionEntities("TEST_COMPONENT_DISTRIBUTION_ENTITIES", "1.0.0")
        Assertions.assertFalse(entities.isEmpty())
        entities.forEach {
            Assertions.assertTrue(it is MavenArtifactDistributionEntity)
            it as MavenArtifactDistributionEntity
            Assertions.assertEquals("org.octopusden.octopus.distribution.server", it.groupId)
            Assertions.assertEquals("app", it.artifactId)
            Assertions.assertEquals("zip", it.extension.orElseThrow { IllegalStateException() })
        }
        Assertions.assertNotNull(entities.find { "windows-x64-nojdk" == (it as MavenArtifactDistributionEntity).classifier.orElseThrow { IllegalStateException() } })
    }

    @ParameterizedTest
    @MethodSource("jiraComponentVersions")
    fun testGetJiraComponentVersion(component: String, version: String, path: String) {
        val jiraComponentVersion = getJiraComponentVersion(component, version)
        val expectedJiraComponentVersion = testDataDir.resolve(path)
            .toObject(JiraComponentVersionDTO::class.java)

        Assertions.assertEquals(expectedJiraComponentVersion, jiraComponentVersion)
    }

    @ParameterizedTest
    @MethodSource("jiraComponentVersionsByProject")
    fun testGetJiraComponentByProjectAndVersion(projectKey: String, version: String, path: String) {
        val jiraComponentVersion = getJiraComponentByProjectAndVersion(projectKey, version)
        val expectedJiraComponentVersion = testDataDir.resolve(path)
            .toObject(JiraComponentVersionDTO::class.java)
        Assertions.assertEquals(expectedJiraComponentVersion, jiraComponentVersion)
    }

    @Test
    fun testGetJiraComponentsByProject() {
        val jiraComponents = getJiraComponentsByProject("SUB")
        Assertions.assertIterableEquals(SUB_COMPONENTS, jiraComponents.sorted())
    }

    @Test
    fun testGetJiraComponentVersionRangesByProject() {
        val jiraComponentVersionRanges = getJiraComponentVersionRangesByProject("SUB")
        val expectedJiraComponentVersionRanges =
            testDataDir.resolve("expected-data/sub-jira-component-version-ranges.json")
                .toObject(object : TypeReference<Set<JiraComponentVersionRangeDTO>>() {})
                .sortedBy { it.componentName }
        Assertions.assertIterableEquals(
            expectedJiraComponentVersionRanges,
            jiraComponentVersionRanges.sortedBy { it.componentName })
    }

    @Test
    fun testGetComponentsDistributionsByJiraProject() {
        val componentDistributions = getComponentsDistributionsByJiraProject("SUB")

        val expectedComponentDistributions = testDataDir.resolve("expected-data/component-distributions.json")
            .toObject(object : TypeReference<Map<String, DistributionDTO>>() {})
            .toSortedMap()
        Assertions.assertIterableEquals(
            expectedComponentDistributions.entries,
            componentDistributions.toSortedMap().entries
        )
    }

    @ParameterizedTest
    @MethodSource("vcsSettings")
    fun testGetVCSSettingForProject(projectKey: String, version: String, expectedPath: String) {
        val vcsSettings = getVCSSettingForProject(projectKey, version)

        val expectedVcsSettings = testDataDir.resolve(expectedPath)
            .toObject(VCSSettingsDTO::class.java)
        Assertions.assertEquals(expectedVcsSettings, vcsSettings)
    }

    @ParameterizedTest
    @MethodSource("distributions")
    fun testGetDistributionForProject(projectKey: String, version: String, path: String) {
        val distribution = getDistributionForProject(projectKey, version)
        val expectedDistribution = testDataDir.resolve(path)
            .toObject(DistributionDTO::class.java)
        Assertions.assertEquals(expectedDistribution, distribution)
    }

    @ParameterizedTest
    @MethodSource("mavenArtifacts")
    fun testGetComponentArtifactsParameters(component: String, path: String) {
        val actualArtifacts = getComponentArtifactsParameters(component)
        val expectedArtifact = testDataDir.resolve(path)
            .toObject(object : TypeReference<Map<String, ComponentArtifactConfigurationDTO>>() {})
        Assertions.assertEquals(expectedArtifact, actualArtifacts)
    }

    @Test
    fun testFindByArtifact() {
        val artifact = testDataDir.resolve("sub-component2-artifact.json").toObject(ArtifactDependency::class.java)
        val actualComponent = findComponentByArtifact(artifact)
        val expectedComponent = testDataDir.resolve("expected-data/sub-component2-versioned-component.json")
            .toObject(VersionedComponent::class.java)
        Assertions.assertEquals(expectedComponent, actualComponent)
    }

    @Test
    fun testFindByArtifactsV3() {
        val artifacts = testDataDir.resolve("sub1-sub2-sub3-artifacts.json")
            .toObject(object : TypeReference<Set<ArtifactDependency>>() {})

        val actualArtifactComponents = findByArtifactsV3(artifacts)
        val expectedArtifactComponents = testDataDir.resolve("expected-data/sub1-sub2-sub3-artifact-components.json")
            .toObject(ArtifactComponentsDTO::class.java)

        Assertions.assertIterableEquals(
            expectedArtifactComponents.artifactComponents,
            actualArtifactComponents.artifactComponents
        )
    }

    protected class Components {
        lateinit var components: MutableList<ComponentV1>
    }

    private fun <T> Path.toObject(javaClass: Class<T>): T {
        return Files.newInputStream(this)
            .use { objectMapper.readValue(it, javaClass) }
    }

    private fun <T> Path.toObject(typeReference: TypeReference<T>): T {
        return Files.newInputStream(this)
            .use { objectMapper.readValue(it, typeReference) }
    }

    companion object {
        @JvmStatic
        fun getTestResourcesPath(): Path = Paths.get(BaseComponentsRegistryServiceTest::class.java.getResource("/expected-data")!!.toURI()).parent

        @JvmStatic
        fun configureSpringAppTestDataDir() {
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", getTestResourcesPath().toString())
        }

        @JvmStatic
        fun jiraComponentVersions(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    "TESTONE",
                    "1.0",
                    "expected-data/testone-1.0-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "versions-api.1.0",
                    "expected-data/versions-api-version-api.1.0-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "1",
                    "expected-data/versions-api-1-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "1.2",
                    "expected-data/versions-api-1.2-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "versions-api.1.2",
                    "expected-data/versions-api-1.2-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "1.2_RC",
                    "expected-data/versions-api-1.2-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "1.2.3",
                    "expected-data/versions-api-1.2.3-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "1.2-0003",
                    "expected-data/versions-api-1.2.3-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "prefix-1.2.3-suffix",
                    "expected-data/versions-api-1.2.3-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "1.2.3.4",
                    "expected-data/versions-api-1.2.3-jira-component-version.json"
                ),
                Arguments.of(
                    "versions-api",
                    "prefix-1.2.3.4-suffix",
                    "expected-data/versions-api-1.2.3-jira-component-version.json"
                ),
            )
        }

        @JvmStatic
        fun vcsSettings(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("SUB", "sub1k-1.0.0", "expected-data/sub-sub1k-1.0.0-vcs-settings.json"),
                Arguments.of("SUB", "hlk-1.0.0", "expected-data/sub-hlk-1.0.0-vcs-settings.json")
            )
        }

        @JvmStatic
        fun distributions(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("SUB", "hlk-1.0.0", "expected-data/sub-hlk-1.0.0-distribution.json"),
                Arguments.of("TESTONE", "1.0", "expected-data/testone-1.0-distribution.json"),
                Arguments.of("TESTONE", "versions-api.1.0", "expected-data/testone-versions-api.1.0-distribution.json")

            )
        }

        @JvmStatic
        fun mavenArtifacts(): Stream<Arguments> = Stream.of(
            Arguments.of("test-release", "expected-data/test-release-artifact.json"),
            Arguments.of("sub-component1", "expected-data/sub-component1-artifact.json"),
            Arguments.of("sub-component2", "expected-data/sub-component2-artifact.json")
        )

        @JvmStatic
        fun jiraComponentVersionsByProject(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("TESTONE", "1.0", "expected-data/testone-1.0-jira-component-version.json"),
                Arguments.of(
                    "TESTONE",
                    "versions-api.1.0",
                    "expected-data/versions-api-version-api.1.0-jira-component-version.json"
                )
            )
        }
    }
}
