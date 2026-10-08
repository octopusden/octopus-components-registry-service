package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.config.AdminConfigProperties

fun componentDefaults(
    buildSystem: String? = null,
    displayName: String? = null,
    copyright: String? = null,
    projectKey: String? = null,
    versionFormat: String? = null,
    line: String? = null,
    minor: String? = null,
    release: String? = null,
    build: String? = null,
    escrowGeneration: String? = null,
    vcsTag: String? = null,
    vcsBranch: String? = null,
) = AdminConfigProperties.ComponentDefaults().apply {
    this.buildSystem = buildSystem
    componentDisplayName = displayName
    this.copyright = copyright
    jira =
        AdminConfigProperties.Jira().apply {
            this.projectKey = projectKey
            componentVersionFormat =
                AdminConfigProperties.Jira.ComponentVersionFormat().apply {
                    this.versionFormat = versionFormat
                    lineVersionFormat = line
                    minorVersionFormat = minor
                    releaseVersionFormat = release
                    buildVersionFormat = build
                }
        }
    escrow = AdminConfigProperties.Escrow().apply { generation = escrowGeneration }
    vcs =
        AdminConfigProperties.Vcs().apply {
            tag = vcsTag
            branch = vcsBranch
        }
}

class ComponentDefaultsSeedTest {
    @Test
    @DisplayName("Decision 6: each component-defaults key the Portal's wizard pre-fills maps to its create-request path")
    fun everyRow() {
        val defaults =
            componentDefaults(
                buildSystem = "GRADLE",
                displayName = "Default name",
                copyright = "Example Corp",
                projectKey = "PLUGINS",
                versionFormat = "\$versionPrefix-\$baseVersionFormat",
                line = "\$major",
                minor = "\$major.\$minor",
                release = "\$major.\$minor.\$service",
                build = "\$major.\$minor.\$service-\$fix",
                escrowGeneration = "AUTO",
                vcsTag = "\$module-\$version",
                vcsBranch = "main",
            )

        assertEquals(
            mapOf(
                "baseConfiguration.build.buildSystem" to "GRADLE",
                "displayName" to "Default name",
                "copyright" to "Example Corp",
                "baseConfiguration.jira.projectKey" to "PLUGINS",
                "baseConfiguration.jira.versionFormat" to "\$versionPrefix-\$baseVersionFormat",
                "baseConfiguration.jira.lineVersionFormat" to "\$major",
                "baseConfiguration.jira.minorVersionFormat" to "\$major.\$minor",
                "baseConfiguration.jira.releaseVersionFormat" to "\$major.\$minor.\$service",
                "baseConfiguration.jira.buildVersionFormat" to "\$major.\$minor.\$service-\$fix",
                "baseConfiguration.escrow.generation" to "AUTO",
                "baseConfiguration.vcsEntries[0].tag" to "\$module-\$version",
                "baseConfiguration.vcsEntries[0].branch" to "main",
            ),
            defaults.templateDefaults(),
        )
    }

    @Test
    @DisplayName("blank and absent defaults are left out")
    fun blankLeftOut() {
        assertEquals(emptyMap<String, String>(), componentDefaults(displayName = "  ", vcsBranch = "").templateDefaults())
        assertEquals(emptyMap<String, String>(), AdminConfigProperties.ComponentDefaults().templateDefaults())
    }

    @Test
    @DisplayName("as the Portal sends them: minor falls back to line, line falls back to minor")
    fun lineAndMinorFallBack() {
        assertEquals(
            mapOf("baseConfiguration.jira.lineVersionFormat" to "\$major", "baseConfiguration.jira.minorVersionFormat" to "\$major"),
            componentDefaults(line = "\$major").templateDefaults(),
        )
        assertEquals(
            mapOf(
                "baseConfiguration.jira.lineVersionFormat" to "\$major.\$minor",
                "baseConfiguration.jira.minorVersionFormat" to "\$major.\$minor",
            ),
            componentDefaults(minor = "\$major.\$minor").templateDefaults(),
        )
    }

    @Test
    @DisplayName("the build format is left out when it equals the release format")
    fun buildEqualToReleaseLeftOut() {
        assertEquals(
            mapOf("baseConfiguration.jira.releaseVersionFormat" to "\$major.\$minor.\$service"),
            componentDefaults(release = "\$major.\$minor.\$service", build = "\$major.\$minor.\$service").templateDefaults(),
        )
    }

    @Test
    @DisplayName("a deprecated default build system is ignored")
    fun deprecatedBuildSystemIgnored() {
        assertEquals(emptyMap<String, String>(), componentDefaults(buildSystem = "BS2_0").templateDefaults())
    }

    @Test
    @DisplayName("Decision 6: no Portal fallback is invented — no branch master, no full version format")
    fun noPortalFallbacks() {
        val seed = componentDefaults(projectKey = "PLUGINS").templateDefaults()

        assertEquals(mapOf("baseConfiguration.jira.projectKey" to "PLUGINS"), seed)
    }
}
