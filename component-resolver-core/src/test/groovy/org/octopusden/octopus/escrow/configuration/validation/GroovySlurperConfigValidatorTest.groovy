package org.octopusden.octopus.escrow.configuration.validation

import java.lang.reflect.Modifier
import org.octopusden.releng.versions.VersionNames

import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.DEB_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.DOCKER_PATTERN_V2
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.GAV_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.GENERIC_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.RPM_PATTERN
import static org.octopusden.octopus.escrow.configuration.validation.GroovySlurperConfigValidator.SUPPORTED_ATTRIBUTES

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
        assert DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/image").matches()
        assert DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/image:arm64").matches()
        assert DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/image,org.octopusden/octopus/image:arm64").matches()
        assert DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/first-image:amd64,org.octopusden/octopus/second-image:amd64").matches()

        assert DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/image:arm64-r2.d2").matches()
        assert DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/image,org.octopusden/octopus/image:arm64,org.octopusden/octopus/image:arm64-r2_d2").matches()
        assert !DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/image:arm64-v2.1").matches()

        assert !DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/first-image:-amd64").matches()
        assert !DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/first-image:1.1-amd64").matches()
        assert !DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/image:\${version}").matches()
        assert !DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/image:\${version}-jdk1").matches()
        assert !DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/first-image:1.0,org.octopusden/octopus/second-image:1.0").matches()
        assert !DOCKER_PATTERN_V2.matcher("org.octopusden\\octopus/image:t10").matches()
        assert !DOCKER_PATTERN_V2.matcher("org.octopusden/octopus:image:t10").matches()
        assert !DOCKER_PATTERN_V2.matcher("org.octopusden/octopus/image:").matches()

    }

    void testDockerField() {
        def verNames = new VersionNames("serviceCBranch", "serviceC", "minorC")

        def correctDockerStrings = ["docker = 'test/test-component,test/path-element/test-component2:amd64'",
                                    "docker = 'test-component4,test-component5:jdk11,test-component5:jdk7_v11'",
        ]

        def incorrectDockerStrings = ["docker = 'test/test-component:\${version},by-\${env.USER}/test/test-component2:1.0'",
                                      "docker = 'test/\${major}/\${minor}/test-component3:\${version}'",
                                      "docker = 'test-component:\${version}'",
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

    void testSupportedAttributesIsStaticFinal() {
        def field = GroovySlurperConfigValidator.class.getDeclaredField('SUPPORTED_ATTRIBUTES')
        assert Modifier.isStatic(field.modifiers)
        assert Modifier.isFinal(field.modifiers)
        assert Modifier.isPublic(field.modifiers)
    }

    void testSupportedAttributesIsUnmodifiable() {
        shouldFail(UnsupportedOperationException) {
            SUPPORTED_ATTRIBUTES.add('extraAttribute')
        }
        shouldFail(UnsupportedOperationException) {
            SUPPORTED_ATTRIBUTES.clear()
        }
    }

    void testSupportedAttributesContainsExpectedEntries() {
        assert SUPPORTED_ATTRIBUTES.contains('buildSystem')
        assert SUPPORTED_ATTRIBUTES.contains('groupId')
        assert SUPPORTED_ATTRIBUTES.contains('artifactId')
    }

    void testGenericAttributeAccepted() {
        def verNames = new VersionNames("serviceCBranch", "serviceC", "minorC")
        def config = new ConfigSlurper().parse("generic = 'releases/foo/\${version}/foo.tar.gz'")
        def validator = new GroovySlurperConfigValidator(verNames)
        validator.validateDistributionSection(config, verNames, "testModule", "testConfig")
        assert !validator.hasErrors()
    }

    void testGenericPattern() {
        // Minimal shape: <pathToArtifact>/<componentVersion>/<artifactName>[.<ext>].
        assert GENERIC_PATTERN.matcher("releases/1.0.0/foo.tar.gz").matches()
        // Extension is optional — Linux executables carry no `.ext`.
        assert GENERIC_PATTERN.matcher("generic-tools/internal-cli/linux-amd64/2.4.1/internal-cli").matches()
        assert GENERIC_PATTERN.matcher("generic-tools/internal-cli/linux-amd64/2.4.2/internal-cli").matches()
        // Extension present — Windows executable / archive.
        assert GENERIC_PATTERN.matcher("generic-tools/internal-cli/windows/2.4.1/internal-cli.exe").matches()
        assert GENERIC_PATTERN.matcher("generic-tools/internal-cli/windows/2.4.2/internal-cli.exe").matches()
        assert GENERIC_PATTERN.matcher("releases/sigma-logan/1.2.3/sigma-logan.tar.gz").matches()
        // Longer multi-segment pathToArtifact.
        assert GENERIC_PATTERN.matcher("releases/sigma-logan/1.2.3/dist/amd64/sigma-logan.tar.gz").matches()
        // Comma-joined list.
        assert GENERIC_PATTERN.matcher(
            "generic-tools/internal-cli/linux-amd64/2.4.1/internal-cli," +
                "generic-tools/internal-cli/windows/2.4.1/internal-cli.exe"
        ).matches()
        // baseUrl is external — reject anything that looks like a full URL.
        assert !GENERIC_PATTERN.matcher("http://example.com/releases/1.0/foo.tar.gz").matches()
        assert !GENERIC_PATTERN.matcher("https://example.com/releases/1.0/foo.tar.gz").matches()
        assert !GENERIC_PATTERN.matcher("file:///opt/foo.tar.gz").matches()
        // Reject leading slash (path is relative to baseUrl).
        assert !GENERIC_PATTERN.matcher("/releases/1.0/foo.tar.gz").matches()
        // Reject query / fragment — they are consumer's business, not the coordinate.
        assert !GENERIC_PATTERN.matcher("releases/1.0/foo.tar.gz?checksum=abc").matches()
        assert !GENERIC_PATTERN.matcher("releases/1.0/foo.tar.gz#sha256").matches()
        // Too few segments (need at least pathToArtifact + componentVersion + artifactName).
        assert !GENERIC_PATTERN.matcher("foo.tar.gz").matches()
        assert !GENERIC_PATTERN.matcher("releases/foo.tar.gz").matches()
        // Whitespace inside a segment.
        assert !GENERIC_PATTERN.matcher("releases/1.0/foo bar.tar.gz").matches()
        // Trailing comma → empty element.
        assert !GENERIC_PATTERN.matcher("releases/1.0/foo.tar.gz,").matches()
        // Empty string.
        assert !GENERIC_PATTERN.matcher("").matches()
    }

    void testGenericValidationRejectsFullUrl() {
        def verNames = new VersionNames("serviceCBranch", "serviceC", "minorC")
        def config = new ConfigSlurper().parse("generic = 'https://example.com/releases/foo/\${version}/foo.tar.gz'")
        def validator = new GroovySlurperConfigValidator(verNames)
        validator.validateDistributionSection(config, verNames, "testModule", "testConfig")
        assert validator.hasErrors()
    }

    void testUnknownDistributionAttributeStillFails() {
        def verNames = new VersionNames("serviceCBranch", "serviceC", "minorC")
        def config = new ConfigSlurper().parse("unknownField = 'value'")
        def validator = new GroovySlurperConfigValidator(verNames)
        validator.validateDistributionSection(config, verNames, "testModule", "testConfig")
        assert validator.hasErrors()
    }

}
