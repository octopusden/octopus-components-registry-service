package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.ReleaseInfo
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import groovy.transform.TypeChecked
import org.junit.Test

import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL
import static org.octopusden.octopus.escrow.TestConfigUtils.escrowConfigurationLoader
import static org.octopusden.octopus.releng.dto.ComponentVersion.create
import static org.junit.Assert.assertEquals

@TypeChecked
class RepositoryResolverTest {

    public static final String TEST_VERSION = "1.12.34-0056"
    public static final String TEST_TEST_COMPONENT2_VERSION = "03.38.30.43"
    public static final String TEST_TEST_COMPONENT2_VERSION_45 = "03.45.30.19"

    private static ReleaseInfoResolver withConfigResolver(String config) {
        return new ReleaseInfoResolver(
                escrowConfigurationLoader(config.endsWith(".groovy") ? config : config + ".groovy"))
    }

    @Test
    void testSeveralVCSRoots() {
        ReleaseInfo component = withConfigResolver("new-vcs/severalVCSRoots").resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION))
        def expected = VersionControlSystemRoot.create("cvs1", CVS, "OctopusSource/Octopus/Intranet",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "TEST_COMPONENT2_03_38_30", "hotfix_branch")
        def expected2 = VersionControlSystemRoot.create("cvs2", CVS, "OctopusSource/Octopus/Module2",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "TEST_COMPONENT2_03_38_30", "hotfix_branch")
        def expected3 = VersionControlSystemRoot.create("mercurial1", MERCURIAL, "ssh://hg@mercurial/zenit",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "default", "hotfix_branch")
        def real = component?.vcsSettings?.versionControlSystemRoots[0]
        def real2 = component?.vcsSettings?.versionControlSystemRoots[1]
        def real3 = component?.vcsSettings?.versionControlSystemRoots[2]
        assert expected == real
        assert expected2 == real2
        assert expected3 == real3

        assert "componentc_db" == component.vcsSettings.externalRegistry
    }

    @Test
    void testSeveralVCSRootsInVersionRangeSection() {
        ReleaseInfo component = withConfigResolver("new-vcs/severalVCSRootsInVersionRangeSection").resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION))
        assert component.vcsSettings.versionControlSystemRoots.size() == 2

        def expected = VersionControlSystemRoot.create("cvs1", CVS, "OctopusSource/Octopus/Intranet",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "TEST_COMPONENT2_03_38_30", null)
        def expected2 = VersionControlSystemRoot.create("mercurial1", MERCURIAL, "ssh://hg@mercurial/zenit",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "default", null)
        def real = component?.vcsSettings?.versionControlSystemRoots[0]
        def real2 = component?.vcsSettings?.versionControlSystemRoots[1]
        assert expected == real
        assert expected2 == real2
    }

    @Test
    void testSingleVCSRootInVCSSettingsSection() {
        ReleaseInfo component = withConfigResolver("new-vcs/singleVCSRootInVCSSettings").resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION))
        def expected = VersionControlSystemRoot.create("main", CVS, "OctopusSource/Octopus/Intranet",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "TEST_COMPONENT2_03_38_30", null)
        def real = component?.vcsSettings?.versionControlSystemRoots[0]
        assert expected == real
    }

    @Test
    void testVCSSettingsMustBeInheritedFromComponentDefaultsToVersionRangeSection() {
        ReleaseInfo component = withConfigResolver("new-vcs/vcsSettingsInheritanceInSection").resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION))
        def expected1 = VersionControlSystemRoot.create("Crc32Crypt", CVS, "OctopusSource/Octopus/Intranet",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "TEST_COMPONENT2_03_38_30", null)
        def expected2 = VersionControlSystemRoot.create("DbJava", MERCURIAL,
                "ssh://hg@mercurial/products/octopusk/DbJava",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "default", null)
        assert [expected1, expected2] == component?.vcsSettings?.versionControlSystemRoots
    }

    @Test
    void testDefaultSettingsForVCSRootsInheritedFromComponentDefaultsShouldBeOverriddenInVersionRangeSection() {
        def component = withConfigResolver("new-vcs/emptyVersionRangeSection").resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION_45))
        def expected = VersionControlSystemRoot.create("Crc32Crypt", MERCURIAL, "OctopusSource/Octopus/Intranet",
                "overridden_tag-${TEST_TEST_COMPONENT2_VERSION_45}", "HEAD", null)
        assert expected == component.vcsSettings.versionControlSystemRoots[0]
        assert [expected] == component.vcsSettings.versionControlSystemRoots
    }

    @Test
    void testInheritanceRootsWithDifferentNamesShouldBeCorrect() {
        def component = withConfigResolver("new-vcs/vcsSettingsWithDifferentNames").resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION))
        def root1 = VersionControlSystemRoot.create("root1", MERCURIAL, "root1-url", "PATCHED_$TEST_TEST_COMPONENT2_VERSION", "patched-root1-branch", "hotfixes/3.38.30")
        def notOverriddenRoot = VersionControlSystemRoot.create("not-overridden-root", CVS, "NOT_URL", "PATCHED_$TEST_TEST_COMPONENT2_VERSION", "NOT_BRANCH", null)
        def newRoot = VersionControlSystemRoot.create("new-root", CVS, "new-root-url", "PATCHED_$TEST_TEST_COMPONENT2_VERSION", "default", null)
        def expectedSettings = VCSSettings.create("component_db_NEW", [root1, notOverriddenRoot, newRoot])
        assert expectedSettings == component.vcsSettings
    }

    @Test
    void testVCSSettingsShouldBeInheritedInVersionRangeWithoutSettings() {
        def resolver = withConfigResolver("new-vcs/emptyVersionRangeSection")
        ReleaseInfo component = resolver.resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION))
        def expected1 = VersionControlSystemRoot.create("Crc32Crypt", CVS, "OctopusSource/Octopus/Intranet",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "TEST_COMPONENT2_03_38_30", null)
        assert [expected1] == component?.vcsSettings?.versionControlSystemRoots
    }

    @Test
    void testTagMustBeInheritedFromComponentDefaultsToVersionRange() {
        def resolver = withConfigResolver("new-vcs/tagInComponentsDefault")
        ReleaseInfo component = resolver.resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION));
        def expected1 = VersionControlSystemRoot.create("main", MERCURIAL, "ssh://hg@mercurial/products/wproject/octopusweb_2_44_3",
                "octopusweb-${TEST_TEST_COMPONENT2_VERSION}", "default", null)
        assert [expected1] == component?.vcsSettings?.versionControlSystemRoots
    }

    @Test
    void testNoVCSRootsInVCSSettings() {
        ReleaseInfo component = withConfigResolver("new-vcs/vcsSettingsOnlyWithDefaults").resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION))
        assert component.vcsSettings != null
        assert component.vcsSettings.hasNoConfiguredVCSRoot()
        assert component.vcsSettings.versionControlSystemRoots.isEmpty()
    }

    @Test
    void testOnlyExternalRegistryPresentsInVCSSettings() {
        ReleaseInfo component = withConfigResolver("new-vcs/vcsSettingsOnlyWithDefaults").resolveRelease(create("external_registry", TEST_TEST_COMPONENT2_VERSION))
        assert component.vcsSettings != null
        assert component.vcsSettings.externalRegistry()
        assert "dwh" == component.vcsSettings.externalRegistry
    }

    @Test
    void testExternalRegistryWithVersionRange() {
        ReleaseInfo component = withConfigResolver("new-vcs/externalRegistryWithVersionRange").resolveRelease(create("torpedo", "1.0"))
        assert component.vcsSettings.externalRegistry() : "$component.vcsSettings for version should have external registry"
        assert component.vcsSettings.notAvailable() : "$component.vcsSettings for version should have external registry"
    }

    @Test
    void testDefaultTag() {
        ReleaseInfo info = withConfigResolver("bcomponent").resolveRelease(create("bcomponent", TEST_VERSION))
        assert info != null
        def expected = VCSSettings.create([VersionControlSystemRoot.create("main", MERCURIAL, "ssh://hg@mercurial/bcomponent", "bcomponent-$TEST_VERSION", "BCOMPONENT-1.12", null)])
        assert expected.versionControlSystemRoots == info.getVcsSettings().versionControlSystemRoots
        assert expected == info.getVcsSettings()
        assert BuildSystem.MAVEN == info.buildSystem
        assert "1.7" == info.buildParameters.javaVersion
    }

    @Test
    void testDefaultBranchByRepositoryType() {
        def resolver = withConfigResolver("new-vcs/defaultBranchByRepositoryType")
        ReleaseInfo info = resolver.resolveRelease(create("bcomponent", TEST_VERSION))
        assert info != null
        def expected = VCSSettings.create([VersionControlSystemRoot.create("main", MERCURIAL, "ssh://hg@mercurial/bcomponent", "bcomponent-$TEST_VERSION", "default", null)])
        assert expected == info.getVcsSettings()

        ReleaseInfo component = resolver.resolveRelease(create("component", TEST_TEST_COMPONENT2_VERSION))
        expected = VersionControlSystemRoot.create("main", CVS, "OctopusSource/Octopus/Intranet",
                "TEST_COMPONENT2_${TEST_TEST_COMPONENT2_VERSION}", "HEAD", null)
        def real = component?.vcsSettings?.versionControlSystemRoots[0]
        assert expected == real
    }

    @Test
    void testDefaultRepository() {
        ReleaseInfo info = withConfigResolver("bcomponent").resolveRelease(create("octopus-parent", "1.1"))
        assert info != null
        assert MERCURIAL ==
                info?.vcsSettings?.getVersionControlSystemRoots()[0].getRepositoryType()
    }

    @Test
    void testBS20_Tags() {
        ReleaseInfo info = withConfigResolver("bcomponent").resolveRelease(create("bcomponent", "1.12.1-149"))
        assertEquals(BuildSystem.BS2_0, info.getBuildSystem())
        assertEquals(CVS, info.getVcsSettings().versionControlSystemRoots[0].getRepositoryType())
        assert "BCOMPONENT-R-1-12-1-149" == info.getVcsSettings().versionControlSystemRoots[0].tag
    }

    @Test
    void testFormattedTags() {
        ReleaseInfo info = withConfigResolver("bcomponent").resolveRelease(create("test-branch", "1.12"))
        def result = info.getVcsSettings().versionControlSystemRoots[0].tag
        assert result == 'test-branch-null'
    }

    @Test
    void testUnknownModule() {
        assert null == withConfigResolver("bcomponent").resolveRelease(create("zenit", "1.9"))
    }

    @Test
    void testDefaultSettingsOfComponent() {
        ReleaseInfo info = withConfigResolver("defaultSettingsOfComponent").resolveRelease(create("component_23", "11.0"))
        def expectedInfo = ReleaseInfo.create(VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", MERCURIAL, "ssh://hg@mercurial/o2/other/component_23", "component_23-R-11.0",  null, null)),
                BuildSystem.MAVEN, null, null, new Distribution(true, false, null, null, null, null, new SecurityGroups(null)), false, null)
        assert expectedInfo == info
    }

    @Test
    void testVersionNotFromVersionRangeInConfig() {
        assert null == withConfigResolver("component_23").resolveRelease(create("component_23", "1.0"))
        assert null != withConfigResolver("component_23").resolveRelease(create("component_23", "10.0.0"))
    }

    @Test
    void testBuildFileLocation() {
        assert "module1/my-pom.xml" ==
                withConfigResolver("single-module/overridenPathToPomXml.groovy").resolveRelease(create("bcomponent", "1.12.1-150")).buildFilePath
    }

    @Test
    void testBuildFilePathInheritance() {
        assert "octopusweb" ==
                withConfigResolver("single-module/overridenPathToPomXml.groovy")
                        .resolveRelease(create("octopuswebapi", "2.44.1-37")).buildFilePath
    }

    @Test
    void testModuleWithDotSigh() {
        assert null != withConfigResolver("single-module/dotSign.groovy").resolveRelease(create("com.sun.mail", "1.1"))
    }


    @Test
    void testFormatterBranch() {
        def result = withConfigResolver("bcomponent.groovy").
                resolveRelease((create("test-branch", "1.2"))).vcsSettings.versionControlSystemRoots[0].branch
        assert result == 'BRANCH-1.2'
    }

}
