package org.octopusden.octopus.components.registry.core.dto

import groovy.transform.CompileStatic

/**
 * ONB-001: Groovy consumers keep calling the six-argument constructor of the v2 DTO after the
 * placement properties are appended (statically compiled, so a missing constructor fails the build).
 */
@CompileStatic
class VersionControlSystemRootDtoGroovyTest extends GroovyTestCase {

    void testSixArgumentConstructor() {
        def dto = new VersionControlSystemRootDTO("main", "ssh://git@example.test/proj/repo-a.git", RepositoryType.GIT,
                "test-component-a-1.0", "master", "hotfix/1.0")

        assertEquals("main", dto.name)
        assertEquals("hotfix/1.0", dto.hotfixBranch)
        assertNull(dto.sourcePath)
        assertNull(dto.checkoutDirectory)
    }

    void testVcsSettingsTwoArgumentConstructor() {
        def root = new VersionControlSystemRootDTO("main", "ssh://git@example.test/proj/repo-a.git", RepositoryType.GIT, null, "master", null)
        def settings = new VCSSettingsDTO([root], null)

        assertEquals([root], settings.versionControlSystemRoots)
        assertNull(settings.buildWorkingDirectory)
    }
}
