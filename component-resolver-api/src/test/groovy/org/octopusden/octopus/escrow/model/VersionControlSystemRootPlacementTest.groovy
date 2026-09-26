package org.octopusden.octopus.escrow.model

import org.octopusden.octopus.escrow.RepositoryType

class VersionControlSystemRootPlacementTest extends GroovyTestCase {

    void testSixArgumentCreateIsUnplaced() {
        def root = VersionControlSystemRoot.create("main", RepositoryType.GIT, "ssh://git@example.test/proj/repo-a.git", null, "master", null)

        assertNull(root.sourcePath)
        assertNull(root.checkoutDirectory)
    }

    void testPlacementCarried() {
        def root = VersionControlSystemRoot.create("feature", RepositoryType.GIT, "ssh://git@example.test/proj/repo-b.git", null, "master", null,
                "data", "feature")

        assertEquals("data", root.sourcePath)
        assertEquals("feature", root.checkoutDirectory)
        assertFalse(root == VersionControlSystemRoot.create("feature", RepositoryType.GIT, "ssh://git@example.test/proj/repo-b.git", null, "master", null))
    }

    void testBuildWorkingDirectoryOnVcsSettings() {
        def roots = [VersionControlSystemRoot.create("core", RepositoryType.GIT, "ssh://git@example.test/proj/core.git", null, "master", null,
                null, "core")]
        def placed = VCSSettings.create(null, roots, "core/mapper")

        assertEquals("core/mapper", placed.buildWorkingDirectory)
        assertNull(VCSSettings.create(null, roots).buildWorkingDirectory)
        assertFalse(placed == VCSSettings.create(null, roots))
        assertFalse(placed.hashCode() == VCSSettings.create(null, roots).hashCode())
        assertTrue(placed.toString().contains("buildWorkingDirectory=core/mapper"))
    }
}
