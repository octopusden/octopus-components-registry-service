package org.octopusden.octopus.escrow

import groovy.transform.TypeChecked
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

import java.util.regex.Pattern

@TypeChecked
class MavenArtifactMatcher {
    Logger log = LogManager.getLogger(MavenArtifactMatcher.class)

    static boolean artifactIdMatches(String artifactId, String artifactPattern) {
        return (artifactPattern == '*' || Pattern.matches(artifactPattern.replaceAll(",", "|"), artifactId))
    }

    static boolean groupIdMatches(String groupId, String groupIdPattern) {
        if (groupIdPattern == null) {
            return false;
        }
        def groupIdItems = groupIdPattern.split(",")
        return groupIdItems.contains(groupId)
    }
}
