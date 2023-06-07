package org.octopusden.octopus.escrow.configuration.model

import org.octopusden.octopus.escrow.model.BuildParameters

class BuildParametersTest extends GroovyTestCase {

    void testParseProperties() {
        def properties = "-Da=b -DjavaVersion=1.8 -Dfindbugs.skip=true"
        def buildParams = BuildParameters.create("1.9", "3", "2.10", false, "", properties, "", [], [])
        assert ["a": "b", "javaVersion": "1.8", "findbugs.skip": "true"] == buildParams.getSystemPropertiesMap()
    }
}
