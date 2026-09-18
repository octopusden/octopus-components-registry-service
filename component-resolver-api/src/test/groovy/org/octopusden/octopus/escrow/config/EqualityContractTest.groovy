package org.octopusden.octopus.escrow.config

import groovy.transform.TypeChecked
import org.octopusden.octopus.escrow.ComponentConfigParserTest
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.Doc
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.VersionNames

/**
 * The equality contracts these models are collected under.
 *
 * `getAllJiraComponentVersionRanges` collects into a `Set`, so whatever `equals` and `hashCode` say
 * here decides which components a public endpoint shows. Two defects made that decision wrong in the
 * permissive direction, and the failure mode is silence: no error, no log line, the component is
 * simply absent. See TD-022 and TD-023.
 */
@TypeChecked
class EqualityContractTest extends GroovyTestCase {

    private static final VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC")
    private static final JiraComponentVersionRangeFactory FACTORY = new JiraComponentVersionRangeFactory(VERSION_NAMES)

    private static JiraComponent jiraComponent() {
        new JiraComponent("TEST_PRJ", null, ComponentConfigParserTest.COMPONENT_VERSION_FORMAT_1,
                new ComponentInfo("MyPrefix", '$versionPrefix-$baseVersionFormat'), true, false)
    }

    private static Distribution distribution(String gav) {
        new Distribution(true, true, gav, null, null, null, new SecurityGroups(null))
    }

    private static JiraComponentVersionRange range(String componentName, String gav) {
        FACTORY.create(componentName, "1.1", jiraComponent(), distribution(gav), VCSSettings.createEmpty())
    }

    /** TD-022: the component name is what the endpoint is keyed by; it cannot be outside equality. */
    void testDifferentComponentsAreNotEqual() {
        def first = range("COMPONENT_ONE", "g:a:jar")
        def second = range("COMPONENT_TWO", "g:a:jar")

        assert first != second
        assert ([first, second] as Set).size() == 2
    }

    /** The same component twice is still one element — the fix must not defeat deduplication. */
    void testSameComponentStillDeduplicates() {
        assert ([range("COMPONENT_ONE", "g:a:jar"), range("COMPONENT_ONE", "g:a:jar")] as Set).size() == 1
    }

    /** TD-023: @EqualsAndHashCode over private fields compares properties, and a private field is not one. */
    void testDistributionsWithDifferentArtifactsAreNotEqual() {
        assert distribution("g:a:jar") != distribution("g:other:jar")
    }

    void testIdenticalDistributionsStayEqual() {
        assert distribution("g:a:jar") == distribution("g:a:jar")
        assert distribution("g:a:jar").hashCode() == distribution("g:a:jar").hashCode()
    }

    /** TD-023, second offender: same shape, smaller blast radius. */
    void testDocsForDifferentComponentsAreNotEqual() {
        assert new Doc("component-one", "1.0") != new Doc("component-two", "1.0")
        assert new Doc("component-one", "1.0") != new Doc("component-one", "2.0")
    }

    void testIdenticalDocsStayEqual() {
        assert new Doc("component-one", "1.0") == new Doc("component-one", "1.0")
    }
}
