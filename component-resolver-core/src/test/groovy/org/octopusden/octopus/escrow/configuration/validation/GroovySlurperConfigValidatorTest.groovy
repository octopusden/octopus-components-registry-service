package org.octopusden.octopus.escrow.configuration.validation


import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.GAV_PATTERN

class GroovySlurperConfigValidatorTest extends GroovyTestCase {

    void testGAVPattern() {
        assert GAV_PATTERN.matcher("org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar").matches()
        assert GAV_PATTERN.matcher("org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar,file:///dir/file").matches()
        assert !GAV_PATTERN.matcher("org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar,file://dir/file").matches()
        assert GAV_PATTERN.matcher("groupId:artifactId:package:classifier,file:/dir/file").matches()
    }
}
