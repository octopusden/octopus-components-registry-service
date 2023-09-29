package org.octopusden.octopus.escrow.configuration.validation

import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.DEB_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.GAV_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.RPM_PATTERN

class GroovySlurperConfigValidatorTest extends GroovyTestCase {

    void testGAVPattern() {
        assert GAV_PATTERN.matcher("org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar").matches()
        assert GAV_PATTERN.matcher("org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar,file:///dir/file").matches()
        assert !GAV_PATTERN.matcher("org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar,file/dir/file").matches()
        assert GAV_PATTERN.matcher("groupId:artifactId:package:classifier,file:/dir/file").matches()
    }

    void testDEBPattern() {
        assert DEB_PATTERN.matcher("foundationdb-clients_1.0.amd64.deb").matches()
        assert DEB_PATTERN.matcher("pool/l/logcomp/logcomp_1.0.54-1_amd64.deb,mesh-agent2_2.0.101-1_amd64.deb").matches()
        assert !DEB_PATTERN.matcher("logcomp_1.0.54-1_amd64.deb,file:///dir/file").matches()
        assert !DEB_PATTERN.matcher("foundationdb clients_1.0.amd64.deb").matches()
    }

    void testRPMPattern() {
        assert RPM_PATTERN.matcher("ansible-2.11.6-7.el8.noarch.rpm").matches()
        assert RPM_PATTERN.matcher("ansible/ansible-2.11.6-7.el8.noarch.rpm,ansible-core-2.11.6-7.el8.noarch.rpm").matches()
        assert !RPM_PATTERN.matcher("ansible-2.11.6-7.el8.noarch.rpm,file:///dir/file").matches()
        assert !RPM_PATTERN.matcher("ansible-2.11.6+7.el8.noarch.rpm").matches()
    }
}
