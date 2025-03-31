package org.octopusden.octopus.escrow.resolvers

import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException
import org.junit.Test
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.escrow.config.ComponentConfig
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.config.JiraComponentVersionRangeFactory
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.ComponentVersionFormat
import org.octopusden.releng.versions.VersionNames

import static JiraParametersResolverTest.createJiraParametersResolverFromConfig
import static org.octopusden.octopus.escrow.JiraProjectVersion.create

@TypeChecked
class ComponentByJiraProjectResolverTest extends GroovyTestCase {

    public static final String TEST_COMPONENT2_VERSION = "customer-component-00.02.34.23"
    public static final String TEST_COMPONENT2_BUILD = TEST_COMPONENT2_VERSION + "-1"
    public static final String SYSTEM_VERSIONS = "system.10.2.3"
    public static final String COMPONENT = "WCOMPONENT"
    public static final VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC")
    public static final JiraComponentVersionRangeFactory JIRA_COMPONENT_VERSION_RANGE_FACTORY = new JiraComponentVersionRangeFactory(VERSION_NAMES)

    private static
    final ComponentVersionFormat COMPONENT_VERSION_FORMAT_1 = ComponentVersionFormat.create('$major.$minor.$service', '$major.$minor.$service-$fix', '$major.$minor.$service-$fix', '$major.$minor.$service', '$major.$minor.$service-$fix.$build');
    private static
    final ComponentVersionFormat COMPONENT_VERSION_FORMAT_2 = ComponentVersionFormat.create('$major.$minor', '$major.$minor.$service', '$major.$minor.$service', '$major.$minor', null);
    private static
    final ComponentVersionFormat MODEL_COMPONENT_VERSION_FORMAT = ComponentVersionFormat.create('Model.$major.$minor.$service', 'Model.$version', 'Model.$version', 'Model.$major.$minor.$service', null);
    private static
    final ComponentVersionFormat MOJO_COMPONENT_VERSION_FORMAT = ComponentVersionFormat.create('Mojo.$major.$minor', 'Mojo.$major.$minor.$service', 'Mojo.$major.$minor.$service', 'Mojo.$major.$minor', null);
    private static final Distribution DISTRIBUTION = new Distribution(true, true, null, null, null, null, new SecurityGroups(null))

    @Test
    void testGetComponentByJiraProject() {
        def resolver = createJiraParametersResolverFromConfig("componentConfig.groovy")
        assert ComponentVersion.create("commoncomponent", SYSTEM_VERSIONS) == resolver.getComponentByJiraProject(create("system", SYSTEM_VERSIONS))
        assert null == resolver.getComponentByJiraProject(create("UNKNOWN", SYSTEM_VERSIONS))
    }

    @Test
    @TypeChecked
    void testGetVCSRootByJiraProject() {
        def resolver = createJiraParametersResolverFromConfig("componentConfig.groovy")

        def vcsSettings = VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", RepositoryType.MERCURIAL, "component-vcs-url", "component-tag", "component-branch"))
        assert vcsSettings == resolver.getVersionControlSystemRootsByJiraProject(create("TEST_COMPONENT2", TEST_COMPONENT2_VERSION));
        // BRANCH355
        assert vcsSettings == resolver.getVersionControlSystemRootsByJiraProject(create("TEST_COMPONENT2", TEST_COMPONENT2_BUILD));

        assert VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", RepositoryType.MERCURIAL, "ssh://hg@mercurial/o2/other/commoncomponent",
                "commoncomponent-tag", "default")) ==
                resolver.getVersionControlSystemRootsByJiraProject(create("system", SYSTEM_VERSIONS))
        assert resolver.getVersionControlSystemRootsByJiraProject(create("UNKNOWN", SYSTEM_VERSIONS)).hasNoConfiguredVCSRoot()

        assert resolver.getVersionControlSystemRootsByJiraProject(create("AS", "1.3")).hasNoConfiguredVCSRoot()

        assert VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", RepositoryType.MERCURIAL, "as-vcs-url", "as-tag", "as-branch")) ==
                resolver.getVersionControlSystemRootsByJiraProject(create("AS", "app-1.6"))
    }


    @Test
    void testGetVCSRootByJiraProjectWithBranchFormat() {
        def resolver = createJiraParametersResolverFromConfig("componentConfig.groovy")

        VCSSettings versionControlSystemRoot = resolver.getVersionControlSystemRootsByJiraProject(create("BRANCH", "1.2.3"))
        assertEquals "BRANCH-1.2", versionControlSystemRoot.getVersionControlSystemRoots()[0].getBranch()
    }
/*
    @Test
    public void testHasJiraProjectBranchesConfigured() {
        def resolver = createJiraParametersResolverFromConfig("componentConfig.groovy")
        assert resolver.hasJiraProjectBranchesConfigured("system");
        assert !resolver.hasJiraProjectBranchesConfigured(COMPONENT)
        assert !resolver.hasJiraProjectBranchesConfigured("UNKNOWN_PRJ")
        assert resolver.hasJiraProjectBranchesConfigured("TEST_COMPONENT2")
        assert !resolver.hasJiraProjectBranchesConfigured("TEST")
    }
  */

    @Test
    @TypeChecked(TypeCheckingMode.SKIP)
    void testGetComponentConfig() {
        def resolver = createJiraParametersResolverFromConfig("componentVersionFormatConfig.groovy")
        ComponentConfig componentConfig = resolver.getComponentConfig()
        assert componentConfig.projectKeyToJiraComponentVersionRangeMap.size() == 3
        def expected = getJiraComponentVersionRangeListByProjectKey()
        def actual = componentConfig.getProjectKeyToJiraComponentVersionRangeMap()."$COMPONENT"
        assertEquals expected, actual

        assert componentConfig.componentNameToJiraComponentVersionRangeMap.size() == 5
        assertEquals getJiraComponentVersionRangeListByComponentNameWeb(), componentConfig.getComponentNameToJiraComponentVersionRangeMap()."octopusweb"
    }

    @Test
    @TypeChecked(TypeCheckingMode.SKIP)
    void testGetComponentConfigWithNewVCSFormat() {
        def resolver = createJiraParametersResolverFromConfig("new-vcs/vcsSettingsInheritanceInSection.groovy")
        ComponentConfig componentConfig = resolver.getComponentConfig()
        def versionRanges = componentConfig.getComponentNameToJiraComponentVersionRangeMap().get("component")
        assert versionRanges.size() == 1
        assert versionRanges[0].vcsSettings != null
        assert versionRanges[0].vcsSettings.versionControlSystemRoots.size() == 2
    }

    private
    static List<JiraComponentVersionRange> getJiraComponentVersionRangeListByProjectKey() throws InvalidVersionSpecificationException {
        ComponentInfo componentInfo = getComponentInfo()
        return Arrays.asList(getJiraComponentVersionRange("octopusweb", "[2.1,)", COMPONENT, COMPONENT_VERSION_FORMAT_1, componentInfo, DISTRIBUTION),
                getJiraComponentVersionRange("octopusweb", "[,2.1)", COMPONENT, COMPONENT_VERSION_FORMAT_2),
                getJiraComponentVersionRange("buildsystem-model", "[1.3,)", COMPONENT, MODEL_COMPONENT_VERSION_FORMAT, null, null,
                        VCSSettings.createEmpty()),
                getJiraComponentVersionRange("buildsystem-mojo", "(,0),[0,)", COMPONENT, MOJO_COMPONENT_VERSION_FORMAT, null, null,
                        VCSSettings.create([VersionControlSystemRoot.create("main", RepositoryType.MERCURIAL,
                                "ssh://hg@mercurial/maven-buildsystem-plugin", 'maven-buildsystem-plugin-$version', null)])))
    }

    private static List<JiraComponentVersionRange> getJiraComponentVersionRangeListByComponentNameWeb() {
        ComponentInfo componentInfo = getComponentInfo()
        return Arrays.asList(getJiraComponentVersionRange("octopusweb", "[2.1,)", COMPONENT, COMPONENT_VERSION_FORMAT_1, componentInfo, DISTRIBUTION),
                getJiraComponentVersionRange("octopusweb", "[,2.1)", COMPONENT, COMPONENT_VERSION_FORMAT_2))
    }

    private static ComponentInfo getComponentInfo() {
        new ComponentInfo("WCOMPONENTBB", '$versionPrefix-$baseVersionFormat')
    }


    private static JiraComponentVersionRange getJiraComponentVersionRange(String componentName,
                                                                          String versionRange,
                                                                          String projectKey,
                                                                          ComponentVersionFormat componentVersionFormat,
                                                                          ComponentInfo componentInfo = null,
                                                                          Distribution distribution = null,
                                                                          VCSSettings vcsSettings = VCSSettings.createEmpty(),
                                                                          boolean technical = false) {
        JiraComponent jiraComponent = getJiraComponent(projectKey, componentVersionFormat, componentInfo, technical)
        return JIRA_COMPONENT_VERSION_RANGE_FACTORY.create(
                componentName,
                versionRange,
                jiraComponent,
                distribution,
                vcsSettings
        )
    }

    private
    static JiraComponent getJiraComponent(String projectKey, ComponentVersionFormat componentVersionFormat, ComponentInfo componentInfo, boolean technical) {
        return new JiraComponent(projectKey, projectKey, componentVersionFormat, componentInfo, technical);
    }
}
