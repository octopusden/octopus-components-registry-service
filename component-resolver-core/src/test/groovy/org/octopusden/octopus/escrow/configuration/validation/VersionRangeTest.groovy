package org.octopusden.octopus.escrow.configuration.validation

import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.junit.Test
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRange
import org.octopusden.releng.versions.VersionRangeFactory

import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue
import static org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader.ALL_VERSIONS

class VersionRangeTest {
    private static final VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC")
    private static final VersionRangeFactory VERSION_RANGE_FACTORY = new VersionRangeFactory(VERSION_NAMES)

    static VersionRange versionRange(String versionRangeStr) {
        return VERSION_RANGE_FACTORY.create(versionRangeStr)
    }

    static void assertHasIntersection(String range1, String range2) {
        assertTrue(versionRange(range1).isIntersect(versionRange(range2)))
    }

    static void assertHasNoIntersection(String range1, String range2) {
        assertFalse(versionRange(range1).isIntersect(versionRange(range2)))
    }

    @Test
    void testIntersection() {
        assertTrue(new DefaultArtifactVersion("1.0.37-0012") > new DefaultArtifactVersion("1.0.37"))
        assertHasNoIntersection("(1.0,2.0]", "(2.0,3.0)")
        assertHasIntersection("(1.0,2.0]", "[2.0,3.0)")
        assertHasIntersection("(1.0,2.0]", ALL_VERSIONS)
        assertHasIntersection("(1.0,2.0)", "[1.0.1]")
        assertHasNoIntersection("(1.0,2.0)", "[2.0.1]")
        assertHasIntersection(ALL_VERSIONS, ALL_VERSIONS)
        assertHasIntersection("[1.0.37-0000, 1.0.38-0000)", "[1.0.37-0012]")
        assertHasIntersection("[1.0.37, 1.0.38)", "[1.0.37-0012]")
    }
}
