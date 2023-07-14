package org.octopusden.octopus.escrow.config

import org.octopusden.octopus.escrow.ComponentConfigParserTest
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import groovy.transform.TypeChecked
import org.octopusden.releng.versions.VersionNames

@TypeChecked
class JiraComponentVersionRangeTest extends GroovyTestCase {

    String TEST_COMPONENT ="TEST_COMPONENT"
    String JIRA_PROJECT="TEST_PRJ"
    String TEST_VERSION = "1.1"
    VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC")

    void testGetJiraComponentVersion() {
        def jiraComponent = new JiraComponent(JIRA_PROJECT, "My display name", ComponentConfigParserTest.COMPONENT_VERSION_FORMAT_1,
                new ComponentInfo("MyPrefix", '$versionPrefix-$baseVersionFormat'), true)
        def distribution = new Distribution(true, true, null, new SecurityGroups(null))
        def builder = JiraComponentVersionRange.builder(VERSION_NAMES)
                .componentName(TEST_COMPONENT)
                .versionRange("[1.0,2)")
                .jiraComponent(jiraComponent)
                .distribution(distribution)
                .vcsSettings(VCSSettings.createEmpty())
        def range = builder.build()
        def expectedComponent = JiraComponentVersion.builder(new JiraComponentVersionFormatter(VERSION_NAMES))
                .componentVersion(ComponentVersion.create(TEST_COMPONENT, TEST_VERSION))
                .component(jiraComponent)
                .build()

        builder.versionRange("1.1")
        assert expectedComponent == builder.build().jiraComponentVersion
    }
}
