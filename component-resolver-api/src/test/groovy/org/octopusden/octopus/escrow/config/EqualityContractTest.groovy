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
 * here decides which components a public endpoint shows. An over-permissive contract fails silently:
 * no error, no log line, the component is simply absent. Each case below pins one term of that
 * contract. See TD-022 and TD-023.
 */
@TypeChecked
class EqualityContractTest extends GroovyTestCase {

    private static final VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC")
    private static final JiraComponentVersionRangeFactory FACTORY = new JiraComponentVersionRangeFactory(VERSION_NAMES)

    private static JiraComponent jiraComponent(String displayName = null) {
        new JiraComponent("TEST_PRJ", displayName, ComponentConfigParserTest.COMPONENT_VERSION_FORMAT_1,
                new ComponentInfo("MyPrefix", '$versionPrefix-$baseVersionFormat'), true, false)
    }

    private static Distribution distribution(String gav) {
        new Distribution(true, true, gav, null, null, null, new SecurityGroups(null))
    }

    private static JiraComponentVersionRange range(String componentName, String gav, String displayName = null) {
        FACTORY.create(componentName, "1.1", jiraComponent(displayName), distribution(gav), VCSSettings.createEmpty())
    }

    /** TD-022: the component name is what the endpoint is keyed by; it cannot be outside equality. */
    void "test RES-026 ranges of two different components are not equal"() {
        def first = range("COMPONENT_ONE", "g:a:jar")
        def second = range("COMPONENT_TWO", "g:a:jar")

        assert first != second
        assert ([first, second] as Set).size() == 2
    }

    /**
     * `JiraComponent.equals` never compared `displayName`, so the name carries no identity of its own
     * — `componentName` is the whole of what separates these two. Written with DIFFERING display
     * names because that is the one input whose hashing the library changed.
     */
    void "test RES-026 differing display names do not separate, and do not need to"() {
        def first = range("COMPONENT_ONE", "g:a:jar", "Component One")
        def second = range("COMPONENT_TWO", "g:a:jar", "Component Two")

        assert first != second
        assert first.hashCode() != second.hashCode()
        assert ([first, second] as Set).size() == 2
    }

    /** The same component twice is still one element: strict enough to distinguish, not to deduplicate. */
    void "test RES-026 the same component twice is still one element"() {
        assert ([range("COMPONENT_ONE", "g:a:jar"), range("COMPONENT_ONE", "g:a:jar")] as Set).size() == 1
    }

    /** TD-023: @EqualsAndHashCode over private fields compares properties, and a private field is not one. */
    void "test RES-026 distributions differing in their artifacts are not equal"() {
        assert distribution("g:a:jar") != distribution("g:other:jar")
    }

    void "test RES-026 identical distributions stay equal"() {
        assert distribution("g:a:jar") == distribution("g:a:jar")
        assert distribution("g:a:jar").hashCode() == distribution("g:a:jar").hashCode()
    }

    /** TD-023, the same shape in `Doc`: a smaller blast radius, an identical contract. */
    void "test RES-026 docs of different components are not equal"() {
        assert new Doc("component-one", "1.0") != new Doc("component-two", "1.0")
        assert new Doc("component-one", "1.0") != new Doc("component-one", "2.0")
    }

    void "test RES-026 identical docs stay equal"() {
        assert new Doc("component-one", "1.0") == new Doc("component-one", "1.0")
        assert new Doc("component-one", "1.0").hashCode() == new Doc("component-one", "1.0").hashCode()
    }
}
