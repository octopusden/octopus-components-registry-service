package org.octopusden.octopus.escrow.configuration.validation.util

import org.octopusden.releng.versions.VersionRange

class VersionRangeHelper {

    public static String ALL_VERSIONS = "(,0),[0,)"

    boolean hasIntersection(VersionRange versionRange1, VersionRange versionRange2) {
        return versionRange1.isIntersect(versionRange2)
    }

}
