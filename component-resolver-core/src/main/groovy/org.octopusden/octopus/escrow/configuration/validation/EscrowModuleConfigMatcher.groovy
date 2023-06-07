package org.octopusden.octopus.escrow.configuration.validation

import org.octopusden.octopus.escrow.MavenArtifactMatcher
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.releng.versions.NumericVersion
import groovy.transform.TypeChecked
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.maven.artifact.Artifact

@TypeChecked
class EscrowModuleConfigMatcher {

    Logger log = LogManager.getLogger(EscrowModuleConfigMatcher.class)

    MavenArtifactMatcher mavenArtifactMatcher = new MavenArtifactMatcher()

    boolean match(Artifact mavenArtifact, EscrowModuleConfig moduleConfig) {
        Objects.requireNonNull(mavenArtifact.version)
        if (mavenArtifactMatcher.groupIdMatches(mavenArtifact.getGroupId(), moduleConfig.getGroupIdPattern()) &&
                mavenArtifactMatcher.artifactIdMatches(mavenArtifact.getArtifactId(), moduleConfig.getArtifactIdPattern())) {
            if (moduleConfig.getVersionRange().containsVersion(NumericVersion.parse(mavenArtifact.getVersion()))) {
                log.debug("$mavenArtifact matches versionRange ${moduleConfig.getVersionRange()}")
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
