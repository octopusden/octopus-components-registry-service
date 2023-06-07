package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.JiraProjectVersion
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import org.octopusden.octopus.releng.dto.ComponentVersion
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.versioning.VersionRange
import org.junit.Ignore
import org.junit.Test

import java.util.regex.Pattern

import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS
import static org.octopusden.octopus.releng.dto.ComponentVersion.create
import static org.octopusden.octopus.escrow.TestConfigUtils.escrowConfigurationLoader

@TypeChecked
class MavenArtifactResolverTest {

    public static final String TEST_VERSION = "1.12.4-200"
    public static final String TEST_VERSION_2 = "1.2.3"
    public static final String TEST_ARTIFACT_ID_2 = "octopus-parent"
    public static final String VERSION_10_0_2 = "10.0.2"
    public static final String VERSION_1_0_36_9999 = "1.0.36-9999"
    public static final String VERSION_1_0_37_0001 = "1.0.37-0001"
    public static final String OVERRIDEN_VERSION = "3.37.29-0012"

    IModuleByArtifactResolver resolver
    //= new ModuleByArtifactResolver(new EscrowConfigurationLoader(new ConfigLoader(getClass().getClassLoader().getResource("testModuleConfig.groovy"))));

    @Test
    @Ignore
    public void testProdConfig() {
        URL url = new File("C:\\projects\\escrow\\components-registry\\src\\main\\resources\\Aggregator.groovy").toURI().toURL()

        def loader = new EscrowConfigurationLoader(
                new ConfigLoader(ComponentRegistryInfo.createFromURL(url)),
                SUPPORTED_GROUP_IDS,
                SUPPORTED_SYSTEMS
        )
        JiraParametersResolver jiraParametersResolver = new JiraParametersResolver(loader, new HashMap<String, String>());
        def vcsRoots = jiraParametersResolver.getVersionControlSystemRootsByJiraProject(JiraProjectVersion.create("MCOMPONENT", "1.0"))
        assert vcsRoots.getVersionControlSystemRoots().size() == 1
        assert "default" == vcsRoots.getVersionControlSystemRoots()[1].branch
    }

    @Test
    public void testExistingArtifact() {
        DefaultArtifact artifact = createArtifact("org.octopusden.octopus.bcomponent", "maven", TEST_VERSION)

        def resolver = withConfigResolver("bcomponent")
        ComponentVersion componentRelease = resolver.resolveComponentByArtifact(artifact)
        assert componentRelease != null
        assert "bcomponent" == componentRelease.componentName
        assert TEST_VERSION == componentRelease.version

        artifact = createArtifact("org.octopusden.octopus", TEST_ARTIFACT_ID_2, TEST_VERSION_2)
        componentRelease = resolver.resolveComponentByArtifact(artifact)
        assert componentRelease != null
        assert TEST_ARTIFACT_ID_2 == componentRelease.componentName
        assert TEST_VERSION_2 == componentRelease.version
    }

    @Test
    void testSeveralGroupIds() {
        def componentRelease = withConfigResolver("severalGroupIds.groovy").resolveComponentByArtifact(createArtifact("org.octopusden.octopus.group", "lm-shared", "1.2.5"))
        assert componentRelease.componentName == "component_apps";
        componentRelease = withConfigResolver("severalGroupIds.groovy").resolveComponentByArtifact(createArtifact("org.octopusden.octopus.comgroup", "lm-pipes-shared", "1.2.5"))
        assert componentRelease.componentName == "component_apps";
    }

    @Test
    void testDoNotInheritArtifactIdFromParentComponent() {
        def componentRelease =
                withConfigResolver("app").resolveComponentByArtifact(createArtifact("org.octopusden.octopus.server.jdk", "release-aggregator-jdk", "1.7.9"))
        assert create("jdk", "1.7.9") == componentRelease
    }

    @Test
    public void testModulesWithSharedGroupId() {
        DefaultArtifact artifact = createArtifact("org.octopusden.octopus.dbsm", "component_client", TEST_VERSION)
        ComponentVersion componentRelease = withConfigResolver("sharedGroupId.groovy").resolveComponentByArtifact(artifact);
        assert "DBSchemeManager-client" == componentRelease.componentName
        assert TEST_VERSION == componentRelease.version

        assert null == withConfigResolver("sharedGroupId.groovy").resolveComponentByArtifact(createArtifact("org.octopusden.octopus.dbsm", "unknown", TEST_VERSION));
    }

    @Test
    @TypeChecked(TypeCheckingMode.SKIP)
    //todo
    public void testArtifactConflict() {
        DefaultArtifact mavenArtifact = createArtifact("org.octopusden.octopus.system", "octopusumessage", TEST_VERSION)

        def s = "ptkmodel2-parent|routingxmltypes|testkxmltypes|octopusumessagecontextxmltypes|ptkmodel2"
        assert !Pattern.matches(s, mavenArtifact.artifactId)

        assert Pattern.matches("octopusumessage", mavenArtifact.artifactId)
        assert !Pattern.matches("routingxmltypes", mavenArtifact.artifactId)


        def componentRelease = withConfigResolver("artifactNameConflictConfig.groovy").resolveComponentByArtifact(mavenArtifact)
        assert "octopusumessage" == componentRelease.componentName
        assert null == withConfigResolver("artifactNameConflictConfig.groovy").resolveComponentByArtifact(createArtifact("org.octopusden.octopus.system", "unknown", TEST_VERSION));
    }

    private IModuleByArtifactResolver withConfigResolver(String config) {
        resolver = new ModuleByArtifactResolver(escrowConfigurationLoader(config.endsWith(".groovy") ? config : config + ".groovy"))
        resolver.reloadComponentsRegistry()
        return resolver
    }

    @Test
    public void testArtifactPatternTrimming() {
        DefaultArtifact artifact = createArtifact("org.octopusden.octopus.buildsystem", "jar-osgifier", TEST_VERSION)
        ComponentVersion componentRelease = withConfigResolver("artifactIdWithWhitespace").resolveComponentByArtifact(artifact)
        assert create("jar-osgifier", TEST_VERSION) == componentRelease;
    }

    @Test
    public void testVersionRange() {
        withConfigResolver("component_23")
        DefaultArtifact artifact = createArtifact("org.octopusden.octopus.system", "component_23", VERSION_1_0_36_9999);

        ComponentVersion componentRelease = resolveArtifact(artifact);
        assert create("system", VERSION_1_0_36_9999) == componentRelease;

        artifact = createArtifact("org.octopusden.octopus.system", "component_23", VERSION_1_0_37_0001);
        assert null == resolver.resolveComponentByArtifact(artifact);

        artifact = createArtifact("org.octopusden.octopus.system", "component_23", VERSION_10_0_2);
        componentRelease = resolveArtifact(artifact);
        assert create("component_23", VERSION_10_0_2) == componentRelease;
    }

    @Test
    void testAnyArtifactId() {
        withConfigResolver("bcomponent")
        DefaultArtifact artifact = createArtifact("org.octopusden.octopus.bcomponent", "Correct_ArtifacT.323-id", TEST_VERSION)
        ComponentVersion componentRelease = resolveArtifact(artifact);
        assert "bcomponent:$TEST_VERSION" == componentRelease.toString();

        artifact = createArtifact("org.octopusden.octopus.test", "zenit@ru", TEST_VERSION)
        componentRelease = resolveArtifact(artifact);
        assert null == componentRelease;
    }

    private ComponentVersion resolveArtifact(DefaultArtifact artifact) {
        resolver.resolveComponentByArtifact(artifact)
    }


    @Test
    public void testResolvingWithOverridingParams() {
        resolver = new ModuleByArtifactResolver(escrowConfigurationLoader("moduleConfigWithParams.groovy"), ["pkgj.version": OVERRIDEN_VERSION]);
        resolver.reloadComponentsRegistry()
        def artifact = resolveArtifact(createArtifact("org.octopusden.octopus.ptkmodel2", "pt_k_packages", OVERRIDEN_VERSION))
        assert artifact != null
    }

    @Test
    public void testSnapshot() {
        withConfigResolver("bcomponent")
        DefaultArtifact artifact = createArtifact("org.octopusden.octopus.bcomponent", "Correct_ArtifacT_323-id", TEST_VERSION + "-SNAPSHOT")
        try {
            assert null == resolver.resolveComponentByArtifact(artifact);
//            assert false : "Exception should be thrown because snapshots are not allowed"
        } catch (ComponentResolverException e) {
            //TODO
//            assert e.getMessage().contains("SNAPSHOTs are not allowed: " + artifact.toString());
        }

    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private DefaultArtifact createArtifact(String groupId, String artifactId, String version) {
        new DefaultArtifact(groupId, artifactId, VersionRange.createFromVersion(version), DefaultArtifact.SCOPE_COMPILE, "jar", "", null);
    }

}
