package org.octopusden.octopus.escrow.configuration.validation

import org.octopusden.releng.versions.VersionNames

import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.DEB_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.GAV_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.RPM_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.DOCKER_PATTERN

class GroovySlurperConfigValidatorTest extends GroovyTestCase {

    void testGAVPattern() {
        assert GAV_PATTERN.matcher("org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar").matches()
        assert GAV_PATTERN.matcher("org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar,file:///dir/file").matches()
        assert !GAV_PATTERN.matcher("org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar,file/dir/file").matches()
        assert GAV_PATTERN.matcher("groupId:artifactId:package:classifier,file:/dir/file").matches()
    }

    void testDEBPattern() {
        assert DEB_PATTERN.matcher("foundationdb-clients_1.0.amd64.deb").matches()
        assert DEB_PATTERN.matcher("pool/l/logcomp/logcomp_1.0.54-1_amd64.deb,meag_2.0.101-1_amd64.deb").matches()
        assert !DEB_PATTERN.matcher("logcomp_1.0.54-1_amd64.deb,file:///dir/file").matches()
        assert !DEB_PATTERN.matcher("foundationdb clients_1.0.amd64.deb").matches()
    }

    void testRPMPattern() {
        assert RPM_PATTERN.matcher("ansible-2.11.6-7.el8.noarch.rpm").matches()
        assert RPM_PATTERN.matcher("ansible/ansible-2.11.6-7.el8.noarch.rpm,ansible-core-2.11.6-7.el8.noarch.rpm").matches()
        assert !RPM_PATTERN.matcher("ansible-2.11.6-7.el8.noarch.rpm,file:///dir/file").matches()
        assert !RPM_PATTERN.matcher("ansible-2.11.6+7.el8.noarch.rpm").matches()
    }

    /*
     *
     */
    void testDockerPattern() {
        assert DOCKER_PATTERN.matcher("org.octopusden/octopus/image:1.0").matches()
        assert DOCKER_PATTERN.matcher("org.octopusden/octopus/first-image:1.0,org.octopusden/octopus/second-image:1.0").matches()
        assert !DOCKER_PATTERN.matcher("org.octopusden/octopus/image").matches()
        assert !DOCKER_PATTERN.matcher("org.octopusden\\octopus/image:1.0").matches()
        assert !DOCKER_PATTERN.matcher("org.octopusden/octopus:image:1.0").matches()
        assert !DOCKER_PATTERN.matcher("org.octopusden/octopus/image:.0").matches()
    }


    void testDockerField() {
        def vn = new VersionNames("serviceCBranch", "serviceC", "minorC")
        def validator = new GroovySlurperConfigValidator(vn)
        def distributionSection = """
        distribution {
            explicit = true
            external = true
            docker = 'test/test-component:${version}'
        }
        """
        def ds = new ConfigSlurper().parse(distributionSection)
        validator.validateDistributionSection(ds, vn, "tst", "tst")
        assert !validator.hasErrors()
    }

}
