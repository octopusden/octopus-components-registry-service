package org.octopusden.octopus.escrow


import org.octopusden.octopus.escrow.model.BuildParameters
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.ComponentVersionFormat
import groovy.transform.TypeChecked
import org.junit.Test
import org.octopusden.releng.versions.VersionNames

@TypeChecked
class ModelConfigPostProcessorTest extends GroovyTestCase {

    static final VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC")

    @Test
    void testComponent() {
        def branch = 'TEST_COMPONENT2_$major02_$minor02_$service02'
        assert "TEST_COMPONENT2_03_38_30" == getModelConfigProcessor("03.38.30.15-23", VERSION_NAMES).resolveVariables(branch)
    }

    void testProcess() {
        ModelConfigPostProcessor modelConfigPostProcessor = getModelConfigProcessor("1.2", VERSION_NAMES)
        def value = '$module-$version'
        assert modelConfigPostProcessor.resolveVariables(value) == "zenit-1.2"
    }

    void testProcessWithReplacement() {
        def value = '$module-${version.replaceAll(\'\\\\.\', \'_\')}'
        ModelConfigPostProcessor modelConfigPostProcessor = getModelConfigProcessor("1.2", VERSION_NAMES)
        def result = modelConfigPostProcessor.resolveVariables(value)
        assert result == 'zenit-null'
    }

    void testProcessCVSCompatibleVersion() {
        ModelConfigPostProcessor modelConfigPostProcessor = getModelConfigProcessor("1.2", VERSION_NAMES)
        def value = '$module-$cvsCompatibleVersion'
        assert modelConfigPostProcessor.resolveVariables(value) == "zenit-1-2"
    }

    void testProcessCVSCompatibleUnderscoreVersion() {
        ModelConfigPostProcessor modelConfigPostProcessor = getModelConfigProcessor("1.2", VERSION_NAMES)
        def value = '$module-$cvsCompatibleUnderscoreVersion'
        assert modelConfigPostProcessor.resolveVariables(value) == "zenit-1_2"
    }

    void testProcessVCSSettings() {
        ModelConfigPostProcessor modelConfigPostProcessor = getModelConfigProcessor("1.2", VERSION_NAMES)
        VCSSettings versionControlSystemRoot = createTestVCSSettings()

        VCSSettings formatted = VCSSettings.createForSingleRoot(VersionControlSystemRoot.create("cvs1", RepositoryType.CVS, 'vcsPath/zenit',
                "zenit-1-2", "zenit_01_02"))

        assert formatted == modelConfigPostProcessor.resolveVariables(versionControlSystemRoot)
    }

    private static VCSSettings createTestVCSSettings() {
        def root = VersionControlSystemRoot.create("cvs1", RepositoryType.CVS, 'vcsPath/$module',
                '$module-$cvsCompatibleVersion', 'zenit_$major02_$minor02')
        VCSSettings.create([root])
    }

    @TypeChecked
    void testReleaseInfo() {
        ModelConfigPostProcessor modelConfigPostProcessor = getModelConfigProcessor("1.2", VERSION_NAMES)


        BuildParameters buildParameters = BuildParameters.create("1.7", "3", "2.10", true, "03.32", '-Dpkgj_version=$version', "build", [], [])
        ReleaseInfo releaseInfo = ReleaseInfo.create(createTestVCSSettings(), BuildSystem.BS2_0, '/buildFilePath/$module', buildParameters,
                new Distribution(true, true, null, null, null, new SecurityGroups(null), null), true, null)

        BuildParameters formattedBuildParameters = BuildParameters.create("1.7", "3", "2.10", true, "03.32", '-Dpkgj_version=1.2', "build", [], [])
        ReleaseInfo formatted = ReleaseInfo.create(modelConfigPostProcessor.resolveVariables(createTestVCSSettings()), BuildSystem.BS2_0,
                'buildFilePath/zenit', formattedBuildParameters, new Distribution(true, true, null, null, null, new SecurityGroups(null), null), true, null)

        assert modelConfigPostProcessor.resolveVariables(releaseInfo) == formatted
    }

    @TypeChecked
    void testProcessJiraComponent() {
        def fullFilledJiraComponent = getJiraComponent("KEY", "displayName", '$major.$minor', '$major.$minor.$service.$fix', '$major.$minor.$service.$fix-$build', '$major.$minor.$service', "", "", false)
        def postProcessor = getModelConfigProcessor("1.2", VERSION_NAMES)
        def actualFullFilledJiraComponent = postProcessor.resolveJiraConfiguration(fullFilledJiraComponent)
        assert fullFilledJiraComponent == actualFullFilledJiraComponent
        def semiFilledJiraComponent = getJiraComponent("KEY", "displayName", '$major.$minor', '$major.$minor.$service.$fix', null, null, "", "", false)
        def expectedSemiFilledJiraComponent = getJiraComponent("KEY", "displayName", '$major.$minor', '$major.$minor.$service.$fix', '$major.$minor.$service.$fix', '$major.$minor', "", "", false)
        def actualSemiFilledJiraComponent = postProcessor.resolveJiraConfiguration(semiFilledJiraComponent)
        assert expectedSemiFilledJiraComponent == actualSemiFilledJiraComponent

    }

    private static ModelConfigPostProcessor getModelConfigProcessor(String version, VersionNames versionNames) {
        new ModelConfigPostProcessor(ComponentVersion.create("zenit", version), versionNames)
    }

    private static JiraComponent getJiraComponent(String projectKey, String displayName, String majorVersionFormat, String releaseVersionFormat, String buildVersionFormat, String lineVersionFormat, String versionPrefix, String versionFormat, boolean isTechincal) {
        new JiraComponent(projectKey, displayName, ComponentVersionFormat.create(majorVersionFormat, releaseVersionFormat, buildVersionFormat, lineVersionFormat), new ComponentInfo(versionPrefix, versionFormat), isTechincal)
    }
}
