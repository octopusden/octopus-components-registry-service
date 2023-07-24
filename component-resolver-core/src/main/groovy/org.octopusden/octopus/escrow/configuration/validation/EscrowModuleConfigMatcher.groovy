package org.octopusden.octopus.escrow.configuration.validation

import groovy.transform.TypeChecked
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.maven.artifact.Artifact
import org.octopusden.octopus.escrow.MavenArtifactMatcher
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory

@TypeChecked
class EscrowModuleConfigMatcher {

    Logger log = LogManager.getLogger(EscrowModuleConfigMatcher.class)

    MavenArtifactMatcher mavenArtifactMatcher = new MavenArtifactMatcher()

    VersionRangeFactory versionRangeFactory
    NumericVersionFactory numericVersionFactory

    EscrowModuleConfigMatcher(VersionRangeFactory versionRangeFactory, NumericVersionFactory numericVersionFactory) {
        this.versionRangeFactory = versionRangeFactory
        this.numericVersionFactory = numericVersionFactory
    }

    boolean match(Artifact mavenArtifact, EscrowModuleConfig moduleConfig) {
        Objects.requireNonNull(mavenArtifact.version)
        if (mavenArtifactMatcher.groupIdMatches(mavenArtifact.getGroupId(), moduleConfig.getGroupIdPattern()) &&
                mavenArtifactMatcher.artifactIdMatches(mavenArtifact.getArtifactId(), moduleConfig.getArtifactIdPattern())) {
            if (versionRangeFactory.create(moduleConfig.getVersionRangeString()).containsVersion(numericVersionFactory.create(mavenArtifact.getVersion()))) {
                log.debug("$mavenArtifact matches versionRange ${moduleConfig.getVersionRangeString()}")
                return true
            } else {
                log.debug("$mavenArtifact doesn't match versionRange {}", moduleConfig.versionRangeString)
            }
        } else {
            log.debug("$mavenArtifact doesn't match group/artifact ${moduleConfig.groupIdPattern}/${moduleConfig.artifactIdPattern}")
        }
        return false
    }
}
