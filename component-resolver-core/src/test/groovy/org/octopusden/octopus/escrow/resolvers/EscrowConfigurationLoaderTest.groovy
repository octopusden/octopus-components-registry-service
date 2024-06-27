package org.octopusden.octopus.escrow.resolvers

import groovy.transform.TypeChecked
import org.junit.Test
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.TestConfigUtils
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.configuration.validation.util.VersionRangeHelper
import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException
import org.octopusden.octopus.escrow.model.BuildParameters
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.Tool
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.ComponentVersionFormat
import org.octopusden.releng.versions.VersionRange

import static BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL
import static org.octopusden.octopus.escrow.TestConfigUtils.NUMERIC_VERSION_FACTORY
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_RANGE_FACTORY
import static org.octopusden.octopus.escrow.TestConfigUtils.loadConfiguration
import static org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader.FAKE_VCS_URL_FOR_BS20

class EscrowConfigurationLoaderTest extends GroovyTestCase {

    public static final String VCS_URL = "ssh://hg@mercurial/bcomponent"
    public static final String VERSION_RANGE = "[1.12.1-150,)"
    public static final String TEST_MODULE = "bcomponent"
    public static
    final BuildParameters DEFAULT_BUILD_PARAMETERS = BuildParameters.create("1.8", "3.3.9", "2.10", false, null, null, null,
            [new Tool(name: "BuildEnv", escrowEnvironmentVariable: "BUILD_ENV", targetLocation: "tools/BUILD_ENV", sourceLocation: "env.BUILD_ENV")], [])


    JiraComponent EMPTY_JIRA_CONFIG = null
    BuildParameters EMPTY_BUILD_CONFIG = null

    @Test
    @TypeChecked
    void testSimpleConfig() {
        EscrowConfiguration configuration = loadConfiguration("single-module/simpleConfig.groovy")
        assert 1 == configuration.escrowModules.size()
        def configurations = configuration.escrowModules.get(TEST_MODULE).moduleConfigurations
        assert configurations.size() == 1
        def escrowModuleConfig = configurations.get(0)
        assert escrowModuleConfig
        escrowModuleConfig.toString()

        def expectedConfig = new EscrowModuleConfig(vcsSettings:
                VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", MERCURIAL, VCS_URL, '$module.$version', 'default')),
                componentOwner: "user",
                releaseManager: "user",
                securityChampion: "user",
                system: "CLASSIC",
                releasesInDefaultBranch: true,
                solution: false,
                buildSystem: BuildSystem.MAVEN,
                artifactIdPattern: "builder",
                groupIdPattern: "io.bcomponent",
                versionRange: VERSION_RANGE,
                jiraConfiguration: new JiraComponent("BCOMPONENT", null, ComponentVersionFormat.create('$major.$minor', '$major.$minor.$service'), new ComponentInfo(null, '$versionPrefix-$baseVersionFormat'), false),
                buildConfiguration:
                        BuildParameters.create(null, null, null, false, null, null, null, [new Tool(name: "BuildEnv", escrowEnvironmentVariable: "BUILD_ENV", targetLocation: "tools/BUILD_ENV",
                                sourceLocation: "env.BUILD_ENV", installScript: "script")], []),
                deprecated: false,
                distribution: new Distribution(true, true, "org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar", null, null, new SecurityGroups(null)),
                componentDisplayName: "BCOMPONENT Official Name")
        assertEquals(expectedConfig.vcsSettings, escrowModuleConfig.vcsSettings)
        assertEquals(expectedConfig, escrowModuleConfig)
    }

    @Test
    void testInvalidVcsPath() {
        shouldFail(EscrowConfigurationException) {
            loadConfiguration("invalidVcsPathConfig.groovy")
        }
    }

    @Test
    void testNoRepository() {
        shouldFail(EscrowConfigurationException) {
            //TODO: check for erros
            loadConfiguration("invalid/noRepository.groovy")
        }
    }

    @Test
    void testPomXmlOverride() {
        EscrowConfiguration configuration = loadConfiguration("single-module/overridenPathToPomXml.groovy")
        assert 2 == configuration.escrowModules.size()
        def configurations = configuration.escrowModules.get(TEST_MODULE).moduleConfigurations
        assert configurations.size() == 1
        def escrowModuleConfig = configurations.get(0)
        assert 'module1/my-pom.xml' == escrowModuleConfig.buildFilePath
    }


    @Test
    void testInvalidVersionRange() {
        try {
            loadConfiguration("invalid/invalidVersionRange.groovy")
            assert false: "test should fail due to invalid version range"
        } catch (EscrowConfigurationException e) {
            assert e.message == "Invalid version range (,) in configuration of bcomponent"
        }
    }

    @Test
    void testVersionRangeWithoutRestrictions() {
        try {
            loadConfiguration("invalid/versionRangeWithoutRestrictions.groovy")
            fail('EscrowException should be thrown')
        } catch (ComponentResolverException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("Version range '1.0' in module 'bcomponent' doesn't satisfy version range syntax/rules: Bad version range: the '[' doesn't specify hard version: 1.0"))
        }
    }

    @Test
    void noVcsUrlInMavenComponent() {
        try {
            loadConfiguration("invalid/noVcsUrl.groovy")
            fail("test should fail due to absent vcsUrl")
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("empty vcsUrl is not allowed in configuration of module bcomponent (type=MAVEN")
        }
    }


    @Test
    void testNoGroupId() {
        try {
            loadConfiguration("invalid/invalidGroupId.groovy")
            fail("test should fail due to absent groupId")
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("empty groupId is not allowed in configuration of module bcomponent (type=MAVEN")
        }
    }

    @Test
    void testAllVersions() {
        EscrowConfiguration configuration = loadConfiguration("single-module/noVersionRange.groovy")
        def configurations = configuration.escrowModules.get(TEST_MODULE).moduleConfigurations
        VersionRangeHelper.ALL_VERSIONS == VERSION_RANGE_FACTORY.create(configurations.get(0).versionRangeString)
    }

    @Test
    void test2Configurations() {
        EscrowConfiguration configuration = loadConfiguration("single-module/2configurations.groovy")
        def configurations = configuration.escrowModules.get(TEST_MODULE).moduleConfigurations
        def expectedConfig1 = new EscrowModuleConfig(
                componentOwner: "user1",
                vcsSettings: VCSSettings.createForSingleRoot(
                        VersionControlSystemRoot.create("main", MERCURIAL, 'ssh://hg@mercurial/bcomponent',
                                '$module-$version', null)),
                buildSystem: MAVEN,
                system: "NONE",
                releasesInDefaultBranch: true,
                solution: false,
                artifactIdPattern: /[\w-]+/,
                groupIdPattern: "org.octopusden.octopus.bcomponent",
                versionRange: "[1.12.1-150,)",
                jiraConfiguration: new JiraComponent("BCOMPONENT", null, ComponentVersionFormat.create('$major.$minor', '$major.$minor.$service'), new ComponentInfo(null, '$versionPrefix-$baseVersionFormat'), false),
                buildConfiguration: EMPTY_BUILD_CONFIG,
                distribution: new Distribution(false, true, null, null, null, new SecurityGroups(null)),
                componentDisplayName: "BCOMPONENT DISPLAY NAME"
        )
        def expectedConfig2 = new EscrowModuleConfig(
                componentOwner: "user1",
                vcsSettings: VCSSettings.create([VersionControlSystemRoot.create("main", CVS, FAKE_VCS_URL_FOR_BS20, '$module-$version', 'default')]),
                buildSystem: BuildSystem.BS2_0,
                system: "NONE",
                releasesInDefaultBranch: true,
                solution: false,
                groupIdPattern: "org.octopusden.octopus.bcomponent",
                artifactIdPattern: /[\w-]+/,
                versionRange: "(,1.12.1-150)",
                jiraConfiguration: new JiraComponent("BCOMPONENT", null, ComponentVersionFormat.create('$major.$minor', '$major.$minor.$service'), new ComponentInfo(null, '$versionPrefix-$baseVersionFormat'), false),
                buildConfiguration: EMPTY_BUILD_CONFIG,
                distribution: null,
                componentDisplayName: "BCOMPONENT DISPLAY NAME"
        )
        assert 2 == configurations.size()
        def config1 = configurations.get(0)
        assert config1 == expectedConfig1


        def config2 = configurations.get(1)
        assert expectedConfig2 == config2
    }

    @Test
    void testDefaults() {
        EscrowConfiguration configuration = loadConfiguration("single-module/defaults.groovy")
        def configurations = configuration.escrowModules.get(TEST_MODULE).moduleConfigurations
        def expectedConfig = new EscrowModuleConfig(
                componentOwner: "user1",
                vcsSettings: VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", MERCURIAL, "ssh://hg@mercurial/bcomponent", '$module-$version', null)),
                buildSystem: MAVEN,
                system: "NONE",
                releasesInDefaultBranch: true,
                solution: false,
                artifactIdPattern: /[\w-]+/,
                groupIdPattern: "org.octopusden.octopus.bcomponent",
                versionRange: "[1.12.1-151,)",
                jiraConfiguration: new JiraComponent("BCOMPONENT", null, ComponentVersionFormat.create('$major.$minor', '$major.$minor.$service'), new ComponentInfo(null, '$versionPrefix-$baseVersionFormat'), false),
                distribution: null,
                buildConfiguration: BuildParameters.create(null, null, null, false, null, null, null,
                        [new Tool(name: "BuildEnv", escrowEnvironmentVariable: "BUILD_ENV", targetLocation: "tools/BUILD_ENV",
                                sourceLocation: "env.BUILD_ENV", installScript: "script")], []
                )
        )
        assert expectedConfig.vcsSettings == configurations.get(0).vcsSettings
        assert expectedConfig == configurations.get(0)
    }

    @Test
    void testAllSettingOfComponentAreDefaults() {
        EscrowConfiguration configuration = loadConfiguration("allSettingsAreDefault.groovy")
        def configurations = configuration.escrowModules.get(TEST_MODULE).moduleConfigurations
        assert 1 == configurations.size()
        def expectedModuleConfig = new EscrowModuleConfig(
                componentOwner: "user1",
                vcsSettings: VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", CVS, "back/build/test/sources/test-maven", '$module-$cvsCompatibleVersion', "bcomponent-branch")),
                buildSystem: MAVEN,
                system: "NONE",
                releasesInDefaultBranch: true,
                solution: false,
                artifactIdPattern: "test-cvs-maven-parent,test-cvs-maven-module1",
                groupIdPattern: "org.octopusden.octopus.bcomponent",
                versionRange: "(,0),[0,)",
                jiraConfiguration: new JiraComponent('TEST', null,
                        ComponentVersionFormat.create('$major', '$major.$minor', '$major.$minor.$build', '$major'), null, true),
                buildFilePath: "test-cvs-maven-parent",
                buildConfiguration: BuildParameters.create('1.7', "3.3", "2.10", false, "03.40.30", "hello", "assemble",
                        [new Tool(name: "BuildEnv", escrowEnvironmentVariable: "BUILD_ENV", targetLocation: "tools/BUILD_ENV", sourceLocation: "env.BUILD_ENV"),
                         new Tool(name: "BuildLib", escrowEnvironmentVariable: "BUILD_LIB", targetLocation: "tools/BuildLib", sourceLocation: "env.BUILD_LIB")
                        ], []),
                deprecated: true,
        )

        assert expectedModuleConfig == configurations.get(0)
    }

    @Test
    void testTeamcityReleaseConfigId() {
        EscrowConfiguration configuration = loadConfiguration("allSettingsWithTcReleaseConfigId.groovy")
        def configurations = configuration.escrowModules.get(TEST_MODULE).moduleConfigurations
        assert 1 == configurations.size()
        def expectedModuleConfig = new EscrowModuleConfig(
                componentOwner: "user1",
                vcsSettings: VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", CVS, "back/build/test/sources/test-maven", '$module-$cvsCompatibleVersion', "bcomponent-branch")),
                buildSystem: BuildSystem.MAVEN,
                system: "NONE",
                releasesInDefaultBranch: true,
                solution: false,
                artifactIdPattern: "test-cvs-maven-parent,test-cvs-maven-module1",
                groupIdPattern: "org.octopusden.octopus.bcomponent",
                versionRange: "(,0),[0,)",
                jiraConfiguration: new JiraComponent('TEST', null,
                        ComponentVersionFormat.create('$major', '$major.$minor', '$major.$minor.$build', '$major'), null, true),
                buildFilePath: "test-cvs-maven-parent",
                buildConfiguration: BuildParameters.create('1.7', "3.3", "2.10", false, "03.40.30", "hello", "assemble",
                        [new Tool(name: "BuildEnv", escrowEnvironmentVariable: "BUILD_ENV", targetLocation: "tools/BUILD_ENV", sourceLocation: "env.BUILD_ENV"),
                         new Tool(name: "BuildLib", escrowEnvironmentVariable: "BUILD_LIB", targetLocation: "tools/BuildLib", sourceLocation: "env.BUILD_LIB")
                        ], []),
                deprecated: true,
        )
        assertEquals(expectedModuleConfig, configurations.get(0))
    }


    @Test
    void testSameComponentNameAndKey() {
        def configuration = loadConfiguration("single-module/sameComponentNameAndKey.groovy")
        def componentConfig = getAndAssertConfiguration(configuration, TEST_MODULE)
        assert componentConfig.componentDisplayName == TEST_MODULE
    }

    @Test
    void testSameMajorAndReleaseVersionFormat() {
        def exception = GroovyAssert.shouldFail(EscrowConfigurationException.class, {
            loadConfiguration("invalid/sameMajorAndReleaseVersionFormat.groovy")
        })
        assert exception.message == "Validation of module config failed due following errors: \n" +
                "releaseVersionFormat is same as majorVersionFormat in component 'bcomponent'"
    }

    @Test
    void testSubComponents() {
        EscrowConfiguration configuration = loadConfiguration("subComponents.groovy")
        assert 5 == configuration.escrowModules.size()

        getAndAssertConfiguration(configuration, TEST_MODULE)
        getAndAssertConfiguration(configuration, "buildsystem-mojo")
        def modelConfiguration = getAndAssertConfiguration(configuration, "buildsystem-model")


        def expectedModuleConfig = new EscrowModuleConfig(
                vcsSettings: VCSSettings.createForSingleRoot(
                        VersionControlSystemRoot.create("main", MERCURIAL, "ssh://hg@mercurial//buildsystem-model",
                                '$module-$version', "1.6-branch")),
                buildSystem: BuildSystem.MAVEN,
                artifactIdPattern: /[\w-\.]+/,
                groupIdPattern: "org.octopusden.octopus.buildsystem.model",
                versionRange: "[1.2,)",
                jiraConfiguration: new JiraComponent('BCOMPONENT', null, ComponentVersionFormat.create('$major.$minor', '$major.$minor.$service',
                        '$major.$minor.$service.$build', '$major'), new ComponentInfo('Model', '$versionPrefix.$baseVersionFormat'), true),
                buildConfiguration: BuildParameters.create("1.6", "1.6-maven", "1.6-gradle", false, "03.1.6", "-D1.6", "build",
                        [new Tool(name: "BuildEnv", escrowEnvironmentVariable: "BUILD_ENV", targetLocation: "tools/BUILD_ENV", sourceLocation: "env.BUILD_ENV"),
                        ], []),
                deprecated: true,
                distribution: new Distribution(false, true, null, null, null, new SecurityGroups(null)),
                componentOwner: "someowner",
                releaseManager: "somereleasemanager",
                securityChampion: "somesecuritychampion",
                system: "CLASSIC",
                releasesInDefaultBranch: false,
                solution: true,
        )
        assert expectedModuleConfig == modelConfiguration

        modelConfiguration = getAndAssertConfiguration(configuration, "notJiraComponent")

        expectedModuleConfig = new EscrowModuleConfig(
                vcsSettings: VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", MERCURIAL,
                        "ssh://hg@mercurial//not-jira-component",
                        '$module-$version',
                        MERCURIAL.getDefaultBranch())),
                buildSystem: BuildSystem.GRADLE,
                artifactIdPattern: "notJiraComponent",
                groupIdPattern: "org.octopusden.octopus.bcomponent",
                versionRange: "(,0),[0,)",
                jiraConfiguration: new JiraComponent("BCOMPONENT", null, ComponentVersionFormat.create('$major.$minor', '$major.$minor.$service-$fix',
                        '$major.$minor.$service.$fix-$build', '$major'), null, false),
                buildConfiguration: BuildParameters.create("1.8", "3.3.9", "2.10", false, null, null, "build",
                        [new Tool(name: "BuildEnv", escrowEnvironmentVariable: "BUILD_ENV", targetLocation: "tools/BUILD_ENV", sourceLocation: "env.BUILD_ENV")], []),
                deprecated: false,
                distribution: new Distribution(true, false, null, null, null, new SecurityGroups(null)),
                componentOwner: "someowner",
                releaseManager: "somereleasemanager",
                securityChampion: "somesecuritychampion",
                system: "CLASSIC",
                releasesInDefaultBranch: false,
                solution: true
        )
        assert expectedModuleConfig == modelConfiguration

        modelConfiguration = getAndAssertConfiguration(configuration, "sub-component-with-defaults")
        expectedModuleConfig = new EscrowModuleConfig(
                vcsSettings: VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("main", CVS, "OctopusSource/zenit", '$module-$version', CVS.getDefaultBranch())),
                buildSystem: BuildSystem.GRADLE,
                artifactIdPattern: /[\w-\.]+/,
                groupIdPattern: "org.octopusden.octopus.buildsystem.sub5",
                versionRange: "(,0),[0,)",
                jiraConfiguration: new JiraComponent("BCOMPONENT", null, ComponentVersionFormat.create('$major.$minor', '$major.$minor.$service-$fix',
                        '$major.$minor.$service.$fix-$build', '$major'), null, false),
                buildConfiguration: DEFAULT_BUILD_PARAMETERS,
                deprecated: false,
                distribution: new Distribution(false, true, null, null, null, new SecurityGroups(null)),
                componentDisplayName: "Human readable sub-component-with-defaults name",
                componentOwner: "Another Owner",
                releaseManager: "anotherreleasemanager",
                securityChampion: "anothersecuritychampion",
                system: "CLASSIC,ALFA",
                releasesInDefaultBranch: true,
                solution: false
        )
        assert expectedModuleConfig == modelConfiguration
    }

    private static EscrowModuleConfig getAndAssertConfiguration(EscrowConfiguration configuration, String moduleName) {
        List testModule = configuration.escrowModules.get(moduleName).moduleConfigurations
        assert 1 == testModule.size()
        testModule.get(0)
    }

    @Test
    void testVersionRangeEverything() {
        VersionRange allVersions = VERSION_RANGE_FACTORY.create(VersionRangeHelper.ALL_VERSIONS)
        assertTrue(allVersions.containsVersion(NUMERIC_VERSION_FACTORY.create("0.0.0")))
        assertTrue(allVersions.containsVersion(NUMERIC_VERSION_FACTORY.create("2.3.50.3")))
        assertTrue(allVersions.containsVersion(NUMERIC_VERSION_FACTORY.create("999.999.0")))
        assertTrue(allVersions.containsVersion(NUMERIC_VERSION_FACTORY.create("1.0.37-0012")))
        assertTrue(allVersions.containsVersion(NUMERIC_VERSION_FACTORY.create("1.0-SNAPSHOT")))
        assertTrue(allVersions.containsVersion(NUMERIC_VERSION_FACTORY.create("38.30.399")))
    }

    @Test
    void testWrongJiraConfiguration() {
        try {
            loadConfiguration("invalid/invalidJiraConf.groovy")
            assert false: 'EscrowException should be thrown'
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("Jira section is not fully configured (majorVersionFormat is not set) in module 'octopusweb'")
            assert e.message.contains("Jira section is not fully configured (releaseVersionFormat is not set) in module 'TEST_COMPONENT7'")
            assert !e.message.contains("projectKey is not set")
        }
    }

    @Test
    void testUnmetSymbolsInVersionFormat() {
        try {
            loadConfiguration("invalid/invalidVersionFormat.groovy")
            assert false: 'EscrowException should be thrown'
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("majorVersionFormat has illegal character(s): octopusweb in component octopusweb")
            assert e.message.contains("releaseVersionFormat has illegal character(s): octopusweb in component octopusweb")
        }

    }

    @Test
    void testWrongGAV() throws Exception {
        try {
            loadConfiguration("invalid/invalidDistributionGAV.groovy")
            assert false: 'EscrowException should be thrown'
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("GAV 'org.octopusden.octopus.bcomponent:build/er:war,org.octopusden.octopus.bcomponent:builder:jar' must match pattern")
        }
    }

    @Test
    void testWrongComponentCustomerConfiguration() {
        def exception = shouldFail(EscrowConfigurationException.class) {
            loadConfiguration("invalid/invalidCustomerComponentConf.groovy")
        }
        assert exception.contains("jira section could not have both customer/component section in  component->(,0),[0,) section of escrow config file")
    }

    @Test
    void testArtifactVersionConflict() {
        def message = shouldFail(EscrowConfigurationException.class,
                {
                    loadConfiguration("artifactVersionConflictConfig.groovy")
                })
        assert message == "Validation of module config failed due following errors: \n" +
                "More than one configuration matches org.octopusden.octopus.system:system. Intersection of version ranges (,0),[0,) with (,0),[0,).\n" +
                "groupId:artifactId patterns of module system has intersection with commoncomponent"
    }

    @Test
    void testFtDataLoading() {
        def l = TestConfigUtils.loadFromURL(ComponentRegistryInfo.createFromFileSystem("src/test/resources/ft-test-data/new", "Aggregator.groovy"))
        l.loadFullConfiguration()
    }

    @Test
    void testInvalidBS20() {
        def message = shouldFail(EscrowConfigurationException) {
            loadConfiguration("invalid/invalidBS2_0VCSSettings.groovy")
        }
        assert message.startsWith("Validation of module config failed due following errors")
        assert message.contains("Several VCS Roots are not allowed for component 'componentWith2VCSRoots' type=BS2_0")
        assert message.contains("vcsUrl must be empty for component 'componentWithVCSUrl' type=BS2_0")
        assert message.contains("No VCS roots is configured for component 'componentWithoutTag' (type=BS2_0)")
    }

    @Test
    void testConfigurationHasMavenArtifactIntersections() {
        def message = shouldFail(EscrowConfigurationException) {
            loadConfiguration("invalid/mavenArtifactIntersection.groovy")
        }

        assert message.contains("Validation of module config failed due following errors: \n" +
                "groupId:artifactId patterns of module")
    }

    @Test
    void testValidationOfExplicitlyDistributedComponentWithoutComponentOwnerShouldFail() {
        def exception = GroovyAssert.shouldFail(EscrowConfigurationException.class, {
            loadConfiguration("invalid/noComponentOwnerInExplictDistributed.groovy")
        })
        assert exception.message == "Validation of module config failed due following errors: \n" +
                "componentDisplayName is not set in 'component1'\n" +
                "componentOwner is not set in 'component2'"
    }

    @Test
    void testValidationOfExplicitlyDistributedComponentWithoutReleaseManager() {
        def exception = GroovyAssert.shouldFail(EscrowConfigurationException.class, {
            loadConfiguration("invalid/noReleaseManagerInExplicitDistributed.groovy")
        })
        assert exception.message == "Validation of module config failed due following errors: \n" +
                "componentDisplayName is not set in 'component1'\n" +
                "releaseManager is not set in 'component2'"
    }

    @Test
    void testValidationOfExplicitlyDistributedComponentWithoutSecurityChampion() {
        def exception = GroovyAssert.shouldFail(EscrowConfigurationException.class, {
            loadConfiguration("invalid/noSecurityChampionInExplicitDistributed.groovy")
        })
        assert exception.message == "Validation of module config failed due following errors: \n" +
                "componentDisplayName is not set in 'component1'\n" +
                "securityChampion is not set in 'component2'\n" +
                "securityChampion is not matched '\\w+(,\\w+)*' in 'component3'"
    }

    @Test
    void testValidationUnsupportedSystem() {
        def exception = GroovyAssert.shouldFail(EscrowConfigurationException.class, {
            loadConfiguration("invalid/invalidSystem.groovy")
        })
        assert exception.message == "Validation of module config failed due following errors: \n" +
                "system contains unsupported values: INVALID in component 'component1'\n" +
                "system is not specified in component 'component2'\n" +
                "system contains unsupported values: INVALID in component 'component3'"
    }

    @Test
    void testValidationWrongFormattedSystem() {
        def exception = GroovyAssert.shouldFail(EscrowConfigurationException.class, {
            loadConfiguration("invalid/wrongFormattedSystem.groovy")
        })
        assert exception.message == "Validation of module config failed due following errors: \n" +
                "system is not matched '\\w+(,\\w+)*' in 'component1'"
    }

    @Test
    void testValidationReleasesInDefaultBranch() {
        def exception = GroovyAssert.shouldFail(EscrowConfigurationException.class, {
            loadConfiguration("invalid/noReleasesInDefaultBranch.groovy")
        })
        assert exception.message == "Validation of module config failed due following errors: \n" +
                "releasesInDefaultBranch is not specified in 'component1'"
    }

    @Test
    void testValidationOfJiraSectionWithoutProjectKey() {
        def exception = GroovyAssert.shouldFail(EscrowConfigurationException.class, {
            loadConfiguration("invalid/noProjectKey.groovy")
        })
        assert exception.message == "Validation of module config failed due following errors: \n" +
                "projectKey is not specified in module 'component1'"
    }

    // BRANCH390
    @Test
    void testValidationShouldBeCorrect() {
        EscrowConfiguration configuration = loadConfiguration("vcsRootValidation.groovy")
        assert 1 == configuration.escrowModules.size()
    }

    @Test
    void testLoadSecurityGroups() {
        EscrowConfiguration configuration = loadConfiguration("testSecurityGroupLoading.groovy")
        assert 2 == configuration.escrowModules.size()
        ["octopusweb"    : "vfiler1-default-rd",
         "mudule-dbModel": "group1"]
                .each { String component, String expectedReadGroups ->
                    def actualReadGroups = configuration.escrowModules[component]
                            .moduleConfigurations[0]
                            .distribution
                            .securityGroups
                            .read
                    assert expectedReadGroups == actualReadGroups
                }
    }

    @Test
    void testValidationSecurityGroups() {
        def exception = GroovyAssert.shouldFail(EscrowConfigurationException.class, {
            loadConfiguration("testSecurityGroupsValidation.groovy")
        })
        assert exception.message.startsWith("Validation of module config failed due to following errors: \n" +
                "Security Groups is not correctly configured in mudule-dbModel. 'group1 ,' does not match")
    }
}
