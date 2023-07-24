package org.octopusden.octopus.escrow.configuration.validation.util

import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRange
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.junit.Test
import org.octopusden.releng.versions.VersionRangeFactory

import static org.octopusden.octopus.escrow.configuration.validation.util.VersionRangeHelper.ALL_VERSIONS
import static junit.framework.Assert.assertTrue
import static org.junit.Assert.assertFalse

class VersionRangeHelperTest {

    private static final VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC")
    private static final VersionRangeFactory VERSION_RANGE_FACTORY = new VersionRangeFactory(VERSION_NAMES)
    private VersionRangeHelper versionRangeHelper = new VersionRangeHelper();

    static VersionRange versionRange(String versionRangeStr) {
        return VERSION_RANGE_FACTORY.create(versionRangeStr)
    }

    @Test
    void testIntersection() {
        VersionRange allVersions =  VERSION_RANGE_FACTORY.create(ALL_VERSIONS)
        assertTrue(new DefaultArtifactVersion("1.0.37-0012").compareTo(new DefaultArtifactVersion("1.0.37")) > 0)
        assertFalse(versionRangeHelper.hasIntersection(versionRange("(1.0,2.0]"), versionRange("(2.0,3.0)")))
        assertTrue(versionRangeHelper.hasIntersection(versionRange("(1.0,2.0]"), versionRange("[2.0,3.0)")))
        assertTrue(versionRangeHelper.hasIntersection(versionRange("(1.0,2.0]"), allVersions))
        assertTrue(versionRangeHelper.hasIntersection(versionRange("(1.0,2.0)"), versionRange("[1.0.1]")))
        assertFalse(versionRangeHelper.hasIntersection(versionRange("(1.0,2.0)"), versionRange("[2.0.1]")))
        assertTrue(versionRangeHelper.hasIntersection(allVersions, allVersions))
        assertHasIntersection("[1.0.37-0000, 1.0.38-0000)", "[1.0.37-0012]")
        assertHasIntersection("[1.0.37, 1.0.38)", "[1.0.37-0012]")

    }

    void assertHasIntersection(String range1, String range2) {
        assertTrue(versionRangeHelper.hasIntersection(versionRange(range1), versionRange(range2)))
    }

    void assertHasNoIntersection(String range1, String range2) {
        assertFalse(versionRangeHelper.hasIntersection(versionRange(range1), versionRange(range2)))
    }
}
