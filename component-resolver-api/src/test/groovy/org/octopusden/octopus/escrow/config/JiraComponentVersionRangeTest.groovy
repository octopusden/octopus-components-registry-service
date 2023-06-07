package org.octopusden.octopus.escrow.config

import org.octopusden.octopus.escrow.ComponentConfigParserTest
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import groovy.transform.TypeChecked

@TypeChecked
class JiraComponentVersionRangeTest extends GroovyTestCase {

    String TEST_COMPONENT ="TEST_COMPONENT"
    String JIRA_PROJECT="TEST_PRJ"
    String TEST_VERSION = "1.1"

    void testGetJiraComponentVersion() {
        def jiraComponent = new JiraComponent(JIRA_PROJECT, "My display name", ComponentConfigParserTest.COMPONENT_VERSION_FORMAT_1,
                new ComponentInfo("MyPrefix", '$versionPrefix-$baseVersionFormat'), true)
        def distribution = new Distribution(true, true, null, new SecurityGroups(null))
        def range = new JiraComponentVersionRange(TEST_COMPONENT, "[1.0,2)", jiraComponent, distribution, VCSSettings.createEmpty())
        def expectedComponent = new JiraComponentVersion(ComponentVersion.create(TEST_COMPONENT, TEST_VERSION), jiraComponent)
        assert expectedComponent == range.getJiraComponentVersion("1.1")
    }
}
