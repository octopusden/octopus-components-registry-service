package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.TestConfigUtils
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponent
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.versioning.VersionRange
import org.junit.Assert
import org.junit.Test

@TypeChecked
class JiraParametersResolverTest extends GroovyTestCase {

    public static final String VERSION1 = "1.12.34"
    public static final String GROUP_ID1 = "org.octopusden.octopus.bcomponent"
    public static final String ARTIFACT_ID1 = "builder"

    public static final String VERSION2 = "11"
    public static final String GROUP_ID2 = "org.octopusden.octopus.system"
    public static final String ARTIFACT_ID2 = "commoncomponent"

    public static final String GROUP_ID_UNKNOWN = "org.octopusden.octopus.unknown"
    public static
    final DefaultArtifact UNKNOWN_GROUP_ID_ARTIFACT = createArtifact(GROUP_ID_UNKNOWN, ARTIFACT_ID1, VERSION1)
    public static final DefaultArtifact ARTIFACT_COMMON = createArtifact(GROUP_ID2, ARTIFACT_ID2, VERSION2)
    public static final DefaultArtifact WEB = createArtifact("org.octopusden.octopus.octopusweb", "jira-integration", "2.3.110")
    public static final DefaultArtifact ARTIFACT = createArtifact(GROUP_ID1, ARTIFACT_ID1, VERSION1)
    public static final ComponentVersion COMPONENT_VERSION = ComponentVersion.create("component", "0.1")
    public static final ComponentVersion TEST_COMPONENT = ComponentVersion.create("TEST_COMPONENT8", "1.0.35")
    public static final ComponentVersion COMPONENT_INFO_INHERITANCE_V1 = ComponentVersion.create("component-info-inheritance", "1.0.35")
    public static final ComponentVersion COMPONENT_INFO_INHERITANCE_V2 = ComponentVersion.create("component-info-inheritance", "2.0.35")

    @Test
    void testGetComponentByMavenArtifact() {
        def componentVersion = getComponentVersion(ARTIFACT, "componentConfig.groovy")

        assertEquals "bcomponent", componentVersion.componentName
        assertEquals VERSION1, componentVersion.version

        componentVersion = getComponentVersion(WEB, "componentConfig.groovy")
        assertEquals "octopusweb", componentVersion.componentName
        assertEquals "2.3.110", componentVersion.version

        assert componentVersion != null
    }

    @Test
    void testGetComponent() {
        def componentInfo = resolve(ARTIFACT)
        assert componentInfo != null
        assert componentInfo.projectKey == "bs-core-jira"
        assert componentInfo.componentVersionFormat.majorVersionFormat == '$major'
        assert componentInfo.componentVersionFormat.releaseVersionFormat == '$major.$minor'
    }

    @Test
    void testGetComponentFromComponentVersionWithCustomerSection() {
        def componentInfo = resolve(COMPONENT_VERSION)
        assert componentInfo != null
        assert componentInfo.projectKey == "TEST_COMPONENT2"
        assert componentInfo.displayName == "TEST_COMPONENT2_CUSTOMER"
        assert componentInfo.componentVersionFormat.majorVersionFormat == '$major02.$minorC.$serviceC'
        assert componentInfo.componentVersionFormat.releaseVersionFormat == '$major02.$minor02.$service02.$fix02'
        assert componentInfo.componentVersionFormat.buildVersionFormat == '$major02.$minor02.$service02.$fix02-$build'
        assert componentInfo.componentVersionFormat.lineVersionFormat == '$major02.$minor02'
        assert componentInfo.componentInfo.versionFormat == '$versionPrefix-$baseVersionFormat'
        assert componentInfo.componentInfo.versionPrefix == 'customer-component'
    }

    @Test
    void testGetComponentFromComponentVersionWithCustomerSectionAndVersionRange() {
        def componentInfo = resolve(TEST_COMPONENT)
        assert componentInfo != null
        assert componentInfo.projectKey == "componentp"
        assert componentInfo.displayName == "MobileWeb Banking"
        assert componentInfo.componentVersionFormat.lineVersionFormat == '$major'
        assert componentInfo.componentVersionFormat.majorVersionFormat == '$major'
        assert componentInfo.componentVersionFormat.releaseVersionFormat == '$major.$minor.$service'
        assert componentInfo.componentVersionFormat.buildVersionFormat == '$major.$minor.$service-$build'
        assert componentInfo.componentInfo.versionFormat == '$versionPrefix-$baseVersionFormat'
        assert componentInfo.componentInfo.versionPrefix == 'acard'

    }

    @Test
    void testGetComponentInfoFromComponentVersion() {
        loadAndCheckComponentInfo(COMPONENT_INFO_INHERITANCE_V1, 'cii', '$versionPrefix-$baseVersionFormat')
        loadAndCheckComponentInfo(COMPONENT_INFO_INHERITANCE_V2, 'cii', '$baseVersionFormat.$versionPrefix')
    }

    @Test
    void testGetComponentFromComponentVersionWithComponentSection() {
        def componentInfo = resolve(ComponentVersion.create("component", "1.2"))
        assert componentInfo != null
        assert componentInfo.projectKey == "TEST_COMPONENT2"
        assert componentInfo.displayName == "TEST_COMPONENT2_CUSTOMER"
        assert componentInfo.componentVersionFormat.majorVersionFormat == '$major02.$minorC.$serviceC'
        assert componentInfo.componentVersionFormat.releaseVersionFormat == '$major02.$minor02.$service02.$fix02'
        assert componentInfo.componentVersionFormat.buildVersionFormat == '$major02.$minor02.$service02.$fix02-$build'
        assert componentInfo.componentVersionFormat.lineVersionFormat == '$major02.$minor02'
        assert componentInfo.componentInfo.versionFormat == '$versionPrefix-$baseVersionFormat'
        assert componentInfo.componentInfo.versionPrefix == 'component-component'
    }

    @Test
    void testGetComponentFromComponentsWithComponentSection() {
        def componentInfo = resolve(ComponentVersion.create("versions-api", "1.2"))
        assert componentInfo != null
        assert componentInfo.projectKey == "TESTONE"
        assert componentInfo.displayName == "VERSIONS API COMPONENT"
        assert componentInfo.componentVersionFormat.majorVersionFormat == '$major'
        assert componentInfo.componentVersionFormat.releaseVersionFormat == '$major.$minor'
        assert componentInfo.componentVersionFormat.buildVersionFormat == '$major.$minor.$build'
        assert componentInfo.componentInfo.versionFormat == '$versionPrefix-$baseVersionFormat'
        assert componentInfo.componentInfo.versionPrefix == "versions-api"
    }

    @Test
    void testIsComponentExistsByComponentVersion() {
        IJiraParametersResolver jiraParametersResolver = createJiraParametersResolverFromConfig("componentConfig.groovy")
        def version = ComponentVersion.create("component", "0.1");
        def versionBad = ComponentVersion.create("component1", "0.1")
        def sysVersion = ComponentVersion.create("commoncomponent", "12.1")
        def sysWrongVersion = ComponentVersion.create("commoncomponent", "1")
        def componentVersion = ComponentVersion.create("sms_component", "5")


        assert jiraParametersResolver.isComponentWithJiraParametersExists(version)
        assert !jiraParametersResolver.isComponentWithJiraParametersExists(versionBad)

        assert jiraParametersResolver.isComponentWithJiraParametersExists(sysVersion)
        assert !jiraParametersResolver.isComponentWithJiraParametersExists(sysWrongVersion)

        assert !jiraParametersResolver.isComponentWithJiraParametersExists(componentVersion)
    }

    @Test
    void testIsComponentExistsByMavenArtifactsParameters() {
        IJiraParametersResolver jiraParametersResolver = createJiraParametersResolverFromConfig("componentConfig.groovy")
        def version = createArtifact("org.octopusden.octopus.componentc", "componentc", "0.1")
        def versionBad = createArtifact("org.octopusden.octopus.components", "components", "0.1")
        def sysArtifact = createArtifact("org.octopusden.octopus.system", "commoncomponent", "12")
        def sysArtifactWrongVersion = createArtifact("org.octopusden.octopus.system", "commoncomponent", "6")
        def smsBanking = createArtifact("org.octopusden.octopus.sms_component", "sms_component", "12")

        assert jiraParametersResolver.isComponentWithJiraParametersExists(version)
        assert !jiraParametersResolver.isComponentWithJiraParametersExists(versionBad)

        assert jiraParametersResolver.isComponentWithJiraParametersExists(sysArtifact)
        assert !jiraParametersResolver.isComponentWithJiraParametersExists(sysArtifactWrongVersion)

        assert !jiraParametersResolver.isComponentWithJiraParametersExists(smsBanking)
    }

    private static JiraComponent resolve(Artifact artifact) {
        return resolve(artifact, "componentConfig.groovy")
    }

    private static JiraComponent resolve(ComponentVersion componentRelease) {
        return resolve(componentRelease, "componentConfig.groovy")
    }


    private static JiraComponent resolve(Artifact artifact, String configFile) {
        IJiraParametersResolver componentResolver = createJiraParametersResolverFromConfig(configFile)
        componentResolver.reloadComponentsRegistry()
        JiraComponent componentInfo = componentResolver.resolveComponent(artifact)
        componentInfo
    }


    private static void loadAndCheckComponentInfo(ComponentVersion componentVersion, String versionPrefix, String versionFormat) {
        def componentV1 = resolve(componentVersion)
        assert componentV1.componentInfo != null
        assert componentV1.componentInfo.versionPrefix == versionPrefix
        assert componentV1.componentInfo.versionFormat == versionFormat
    }

    private static ComponentVersion getComponentVersion(Artifact artifact, String configFile) {
        IJiraParametersResolver componentResolver = createJiraParametersResolverFromConfig(configFile)
        return componentResolver.getComponentByMavenArtifact(artifact)
    }

    private
    static JiraComponent resolve(ComponentVersion componentRelease, String configFile) {
        def componentResolver = createJiraParametersResolverFromConfig(configFile)
        def componentInfo = componentResolver.resolveComponent(componentRelease)
        componentInfo
    }

    static IJiraParametersResolver createJiraParametersResolverFromConfig(String configFile) {
        EscrowConfigurationLoader escrowConfigurationLoader = TestConfigUtils.escrowConfigurationLoader(configFile)
        JiraParametersResolver componentResolver = new JiraParametersResolver(escrowConfigurationLoader, [:] as HashMap)
        componentResolver.reloadComponentsRegistry()
        componentResolver
    }

    @Test
    void testGetComponentWithAnyArtifactIdInConfiguration() {
        def componentInfo = resolve(WEB)
        assert componentInfo != null
        assert componentInfo.projectKey == "WCOMPONENT"
        assert componentInfo.componentVersionFormat.majorVersionFormat == '$major.$minor'
        assert componentInfo.componentVersionFormat.releaseVersionFormat == '$major.$minor.$service'
    }

    @Test
    void testGetComponentWithDefaultConfiguration() {
        def componentInfo = resolve(ARTIFACT_COMMON);
        assert null != componentInfo;
        assert componentInfo.displayName == "SYSTEM_CUSTOMER"
        assert componentInfo.projectKey == "system"
        assert componentInfo.componentVersionFormat.majorVersionFormat == '$major.$minor'
        assert componentInfo.componentVersionFormat.lineVersionFormat == null
        assert componentInfo.componentVersionFormat.releaseVersionFormat == '$major.$minor.$service'
        assert componentInfo.componentVersionFormat.buildVersionFormat == '$major.$minor.$service-$build'
        assert componentInfo.componentInfo.versionFormat == '$versionPrefix.$baseVersionFormat'
        assert componentInfo.componentInfo.versionPrefix == "system"

    }

    @Test
    void testUnknownComponent() {
        try {
            resolve(UNKNOWN_GROUP_ID_ARTIFACT)
            Assert.fail("${EscrowConfigurationException.class.name} expected")
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("$GROUP_ID_UNKNOWN:$ARTIFACT_ID1:jar:$VERSION1");
        }
    }

    @Test
    void testNewJiraConfigurationFormat() {
        def componentInfo = resolve(ARTIFACT, "newComponentConfig.groovy");
        assert componentInfo != null
        assert componentInfo.projectKey == "bs-core-jira-new"
        assert componentInfo.componentVersionFormat.majorVersionFormat == '$major'
        assert componentInfo.componentVersionFormat.releaseVersionFormat == '$major.$minor'
        assert componentInfo.componentVersionFormat.buildVersionFormat == '$major.$minor.$build'

        componentInfo = resolve(WEB, "newComponentConfig.groovy");
        assert componentInfo != null
        assert componentInfo.projectKey == "WCOMPONENT"
        assert componentInfo.componentVersionFormat.majorVersionFormat == '$major.$minor'
        assert componentInfo.componentVersionFormat.releaseVersionFormat == '$major.$minor.$service'
        assert componentInfo.componentVersionFormat.buildVersionFormat == '$major.$minor.$service.$build'
    }

    @Test
    void testJiraParametersForSubComponents() {
        def componentInfo = resolve(ComponentVersion.create("buildsystem-model", "1.2"), "subComponents.groovy")
        assert componentInfo != null;

        componentInfo = resolve(ComponentVersion.create("notJiraComponent", "1.0"), "subComponents.groovy")
        assert componentInfo != null;
    }



   @TypeChecked(TypeCheckingMode.SKIP)
    private static DefaultArtifact createArtifact(String groupId, String artifactId, String version) {
        new DefaultArtifact(groupId, artifactId, VersionRange.createFromVersion(version), DefaultArtifact.SCOPE_COMPILE, "jar", "", null);
    }

}
