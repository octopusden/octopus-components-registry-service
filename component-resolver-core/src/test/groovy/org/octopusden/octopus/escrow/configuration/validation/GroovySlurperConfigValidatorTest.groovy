package org.octopusden.octopus.escrow.configuration.validation

import org.octopusden.releng.versions.VersionNames

import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.DEB_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.DOCKER_PATTERN_NEW
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.GAV_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.RPM_PATTERN
// -- DOCKER -- to be removed
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.DOCKER_PATTERN_OLD

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
     * Docker pattern should contain only one image name without tag
     */
    void testDockerPattern() {
        assert DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/image").matches()
        assert DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/image:arm64").matches()
        assert DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/image,org.octopusden/octopus/image:arm64").matches()
        assert DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/first-image:amd64,org.octopusden/octopus/second-image:amd64").matches()
        assert !DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/first-image:-amd64").matches()
        assert !DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/first-image:1.1-amd64").matches()
        assert !DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/image:\${version}").matches()
        assert !DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/image:\${version}-jdk1").matches()
        assert !DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/first-image:1.0,org.octopusden/octopus/second-image:1.0").matches()
        assert !DOCKER_PATTERN_NEW.matcher("org.octopusden\\octopus/image:t10").matches()
        assert !DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus:image:t10").matches()
        assert !DOCKER_PATTERN_NEW.matcher("org.octopusden/octopus/image:").matches()

        // -- DOCKER -- to be removed
        assert DOCKER_PATTERN_OLD.matcher("org.octopusden/octopus/image:1.0").matches()
        assert DOCKER_PATTERN_OLD.matcher("org.octopusden/octopus/first-image:1.0,org.octopusden/octopus/second-image:1.0").matches()
        assert !DOCKER_PATTERN_OLD.matcher("org.octopusden/octopus/image").matches()
        assert !DOCKER_PATTERN_OLD.matcher("org.octopusden\\octopus/image:1.0").matches()
        assert !DOCKER_PATTERN_OLD.matcher("org.octopusden/octopus:image:1.0").matches()
        assert !DOCKER_PATTERN_OLD.matcher("org.octopusden/octopus/image:.0").matches()

    }


    void testDockerField() {
        def verNames = new VersionNames("serviceCBranch", "serviceC", "minorC")

        def correctDockerStrings = ["docker = 'test/test-component:\${version},test/path-element/test-component2:11.22'",
                                    "docker = 'test-component4:11.22'",
                                    "docker = 'test-component4:\${version},test-component5:\${version}-jdk11,test-component5:\${minor}-jdk7'",
        ]

        def incorrectDockerStrings = ["docker = 'test/test-component:\${version},by-\${env.USER}/test/test-component2:1.0'",
                                      "docker = 'test/\${major}/\${minor}/test-component3:\${version}'",
                                      "docker = 'test-component\${version}:11.22'",
                                      "docker = 'test/\${baseDir}/test-component:1.0'",
                                      "docker = 'test/\${abrakadabra}/test-component:1.0'"]

        correctDockerStrings.forEach {
            def correct = new ConfigSlurper().parse(it)
            def validator = new GroovySlurperConfigValidator(verNames)
            validator.validateDistributionSection(correct, verNames, "testModule", "testConfig")
            assert !validator.hasErrors()
        }

        int errorCount = 0
        incorrectDockerStrings.forEach {
            def inCorrect = new ConfigSlurper().parse(it)
            def validator = new GroovySlurperConfigValidator(verNames)
            validator.validateDistributionSection(inCorrect, verNames, "testModule", "testConfig")
            if (validator.hasErrors()) {
                errorCount++
            }
        }
        assert errorCount == incorrectDockerStrings.size()
    }

}
