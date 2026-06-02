package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import java.util.UUID

/**
 * Golden / structural tests for [ComponentCodeRenderer]. The full minimal case is
 * asserted as an exact string (locks indentation + format); the richer scenarios
 * assert on the salient lines/blocks so they stay robust against unrelated field
 * additions.
 */
class ComponentCodeRendererTest {
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val renderer =
        ComponentCodeRenderer(
            VersionRangeFactory(versionNames),
            NumericVersionFactory(versionNames),
        )

    // ----------------------------------------------------------------------
    // Fixtures
    // ----------------------------------------------------------------------

    private fun component(key: String = "bcomponent"): ComponentEntity =
        ComponentEntity(id = UUID.randomUUID(), componentKey = key)

    private fun base(
        c: ComponentEntity,
        synthetic: Boolean = false,
        block: ComponentConfigurationEntity.() -> Unit = {},
    ): ComponentConfigurationEntity {
        val row =
            ComponentConfigurationEntity(
                id = UUID.randomUUID(),
                component = c,
                versionRange = "(,0),[0,)",
                overriddenAttribute = null,
                rowType = "BASE",
                isSyntheticBase = synthetic,
            ).apply(block)
        c.configurations.add(row)
        return row
    }

    private fun scalarOverride(
        c: ComponentEntity,
        range: String,
        attribute: String,
        block: ComponentConfigurationEntity.() -> Unit,
    ) {
        c.configurations.add(
            ComponentConfigurationEntity(
                id = UUID.randomUUID(),
                component = c,
                versionRange = range,
                overriddenAttribute = attribute,
                rowType = "SCALAR_OVERRIDE",
            ).apply(block),
        )
    }

    private fun marker(
        c: ComponentEntity,
        range: String,
        attribute: String,
        block: ComponentConfigurationEntity.() -> Unit,
    ): ComponentConfigurationEntity {
        val row =
            ComponentConfigurationEntity(
                id = UUID.randomUUID(),
                component = c,
                versionRange = range,
                overriddenAttribute = attribute,
                rowType = "MARKER",
            ).apply(block)
        c.configurations.add(row)
        return row
    }

    @Suppress("LongParameterList")
    private fun vcs(
        cfg: ComponentConfigurationEntity,
        name: String,
        path: String,
        branch: String? = null,
        repo: String? = null,
        order: Int = 0,
    ) = VcsSettingsEntryEntity(
        componentConfiguration = cfg,
        name = name,
        vcsPath = path,
        branch = branch,
        repositoryType = repo,
        sortOrder = order,
    )

    private fun docker(
        cfg: ComponentConfigurationEntity,
        image: String,
        flavor: String? = null,
        order: Int = 0,
    ) = DistributionDockerImageEntity(
        componentConfiguration = cfg,
        imageName = image,
        flavor = flavor,
        sortOrder = order,
    )

    // ----------------------------------------------------------------------
    // FULL — exact golden for the minimal shape
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("FULL: minimal component renders exact Groovy shape (indentation, quote styles)")
    fun fullMinimalExact() {
        val c = component("bcomponent").also { it.componentOwner = "user1" }
        base(c) {
            buildSystem = "MAVEN"
            jiraProjectKey = "BS"
            jiraMajorVersionFormat = "\$major"
        }

        val expected =
            """
            bcomponent {
                componentOwner = "user1"
                build {
                    buildSystem = MAVEN
                }
                jira {
                    projectKey = "BS"
                    majorVersionFormat = '${'$'}major'
                }
            }
            """.trimIndent() + "\n"

        assertEquals(expected, renderer.renderFull(c))
    }

    @Test
    @DisplayName("FULL: no-override component is a single block with no range sub-blocks")
    fun fullNoOverrideSingleBlock() {
        val c = component().also { it.componentOwner = "u" }
        base(c) { buildSystem = "GRADLE" }
        val out = renderer.renderFull(c)
        assertFalse(out.contains("\"(,") || out.contains("\"[")) // no range header
    }

    // ----------------------------------------------------------------------
    // FULL — per-range override block (delta-style)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("FULL: scalar override becomes a \"<range>\" block carrying only the overridden field")
    fun fullScalarOverrideRangeBlock() {
        val c = component()
        base(c) { jiraReleaseVersionFormat = "\$major" }
        scalarOverride(c, "[1.5,)", "jira.releaseVersionFormat") { jiraReleaseVersionFormat = "\$major.\$minor" }

        val out = renderer.renderFull(c)
        assertTrue(out.contains("\"[1.5,)\" {"), out)
        assertTrue(out.contains("releaseVersionFormat = '\$major.\$minor'"), out)
    }

    @Test
    @DisplayName("FULL: null-clear scalar override renders `field = null` (presence, not value)")
    fun fullNullClearRendersNull() {
        val c = component()
        base(c) { buildFilePath = "build.gradle" }
        // import-only null-clear: SCALAR_OVERRIDE row exists, typed column is null
        scalarOverride(c, "[2,3)", "build.buildFilePath") { buildFilePath = null }

        val out = renderer.renderFull(c)
        assertTrue(out.contains("\"[2,3)\" {"), out)
        assertTrue(out.contains("buildFilePath = null"), out)
    }

    @Test
    @DisplayName("FULL: import-internal marker-only range (group-artifact-pattern) does not emit an empty block")
    fun fullImportMarkerOnlyRangeSkipped() {
        val c = component()
        base(c) { buildSystem = "MAVEN" }
        // MIG-047: group-artifact-pattern is an import-internal MARKER not in MarkerAttributes.ALL.
        marker(c, "[5,)", MarkerAttributes.GROUP_ARTIFACT_PATTERN) {}
        val out = renderer.renderFull(c)
        assertFalse(out.contains("\"[5,)\""), out)
    }

    @Test
    @DisplayName("FULL: RANGE_PRESENCE-only range does not emit an empty block")
    fun fullRangePresenceSkipped() {
        val c = component()
        base(c) { buildSystem = "MAVEN" }
        c.configurations.add(
            ComponentConfigurationEntity(
                id = UUID.randomUUID(),
                component = c,
                versionRange = "[9,10)",
                overriddenAttribute = null,
                rowType = "RANGE_PRESENCE",
            ),
        )
        val out = renderer.renderFull(c)
        assertFalse(out.contains("\"[9,10)\""), out)
    }

    // ----------------------------------------------------------------------
    // FULL — child collections (vcs, distribution, build markers)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("FULL: multiple VCS roots render as named sub-blocks")
    fun fullMultiVcs() {
        val c = component()
        val b = base(c) { buildSystem = "MAVEN" }
        b.vcsEntries.add(vcs(b, name = "core", path = "org/core", repo = "GIT", order = 0))
        b.vcsEntries.add(vcs(b, name = "ui", path = "org/ui", repo = "GIT", order = 1))

        val out = renderer.renderFull(c)
        assertTrue(out.contains("vcsSettings {"), out)
        assertTrue(out.contains("\"core\" {"), out)
        assertTrue(out.contains("\"ui\" {"), out)
        assertTrue(out.contains("vcsUrl = \"org/core\""), out)
        assertTrue(out.contains("repositoryType = GIT"), out)
    }

    @Test
    @DisplayName("FULL: single unnamed VCS root renders flat (no named sub-block)")
    fun fullSingleVcsFlat() {
        val c = component()
        val b = base(c) { buildSystem = "MAVEN" }
        b.vcsEntries.add(vcs(b, name = "main", path = "ssh://git/x.git", branch = "master"))

        val out = renderer.renderFull(c)
        assertTrue(out.contains("vcsSettings {"), out)
        assertFalse(out.contains("\"main\" {"), out)
        assertTrue(out.contains("vcsUrl = \"ssh://git/x.git\""), out)
        assertTrue(out.contains("branch = \"master\""), out)
    }

    @Test
    @DisplayName("FULL: build markers (requiredTools + buildTools beans) render inside build block")
    fun fullBuildMarkers() {
        val c = component()
        val b = base(c) { buildSystem = "MAVEN" }
        b.requiredToolJunctions.add(ComponentRequiredToolEntity(componentConfigurationId = b.id!!, toolName = "BuildEnv"))
        b.buildToolBeans.add(
            ComponentBuildToolBeanEntity(
                id = UUID.randomUUID(),
                componentConfiguration = b,
                beanType = "oracleDatabase",
                toolType = "ORACLE",
                versionPattern = "[12,)",
                edition = "ENTERPRISE",
                sortOrder = 0,
            ),
        )
        val out = renderer.renderFull(c)
        assertTrue(out.contains("requiredTools = [\"BuildEnv\"]"), out)
        assertTrue(out.contains("buildTools {"), out)
        assertTrue(out.contains("beanType = \"oracleDatabase\""), out)
        assertTrue(out.contains("edition = \"ENTERPRISE\""), out)
    }

    @Test
    @DisplayName("FULL: distribution docker child renders inside distribution block")
    fun fullDistributionDocker() {
        val c = component()
        val b = base(c) { buildSystem = "MAVEN" }
        b.dockerImages.add(docker(b, image = "acme/svc", flavor = "slim"))

        val out = renderer.renderFull(c)
        assertTrue(out.contains("distribution {"), out)
        assertTrue(out.contains("docker {"), out)
        assertTrue(out.contains("imageName = \"acme/svc\""), out)
        assertTrue(out.contains("flavor = \"slim\""), out)
    }

    @Test
    @DisplayName("FULL: per-range vcs.settings marker renders a vcsSettings block in the range")
    fun fullRangeVcsMarker() {
        val c = component()
        base(c) { buildSystem = "MAVEN" }
        val m = marker(c, "[2,)", MarkerAttributes.VCS_SETTINGS) {}
        m.vcsEntries.add(vcs(m, name = "main", path = "org/v2", branch = "v2"))

        val out = renderer.renderFull(c)
        assertTrue(out.contains("\"[2,)\" {"), out)
        assertTrue(out.contains("vcsUrl = \"org/v2\""), out)
    }

    // ----------------------------------------------------------------------
    // FULL — people, escaping, key quoting, synthetic base
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("FULL: multi-value people render as list literals")
    fun fullMultiValuePeople() {
        val c = component()
        c.replaceReleaseManagerUsernames(listOf("alice", "bob"))
        c.replaceSecurityChampionUsernames(listOf("carol"))
        base(c) { buildSystem = "MAVEN" }

        val out = renderer.renderFull(c)
        assertTrue(out.contains("releaseManager = [\"alice\", \"bob\"]"), out)
        assertTrue(out.contains("securityChampion = [\"carol\"]"), out)
    }

    @Test
    @DisplayName("FULL: strings escape quotes/backslashes; \$ values use single quotes")
    fun fullEscaping() {
        val c = component().also { it.displayName = "a\"b\\c" }
        base(c) {
            buildSystem = "MAVEN"
            jiraVersionFormat = "\$prefix-\$base"
        }
        val out = renderer.renderFull(c)
        assertTrue(out.contains("displayName = \"a\\\"b\\\\c\""), out)
        assertTrue(out.contains("versionFormat = '\$prefix-\$base'"), out)
    }

    @Test
    @DisplayName("FULL: component key with special chars is quoted in the block header")
    fun fullKeyQuoting() {
        val c = component("buildsystem-model")
        base(c) { buildSystem = "MAVEN" }
        assertTrue(renderer.renderFull(c).startsWith("\"buildsystem-model\" {"))
    }

    @Test
    @DisplayName("FULL: synthetic base with overrides suppresses base row aspects but keeps range blocks")
    fun fullSyntheticBaseSuppression() {
        val c = component()
        base(c, synthetic = true) { buildSystem = "MAVEN" }
        scalarOverride(c, "[1,2)", "jira.projectKey") { jiraProjectKey = "AAA" }

        val out = renderer.renderFull(c)
        assertFalse(out.contains("buildSystem"), "synthetic base row aspects must be suppressed:\n$out")
        assertTrue(out.contains("\"[1,2)\" {"), out)
        assertTrue(out.contains("projectKey = \"AAA\""), out)
    }

    // ----------------------------------------------------------------------
    // RESOLVED
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("RESOLVED: scalar override merges in for an in-range version; base value otherwise")
    fun resolvedScalarMerge() {
        val c = component()
        base(c) { javaVersion = "1.8" }
        scalarOverride(c, "[1.5,)", "build.javaVersion") { javaVersion = "11" }

        val inRange = renderer.renderResolved(c, "2.0")!!
        val outOfRange = renderer.renderResolved(c, "1.0")!!
        assertTrue(inRange.contains("javaVersion = \"11\""), inRange)
        assertTrue(outOfRange.contains("javaVersion = \"1.8\""), outOfRange)
        // No range sub-blocks in a resolved view.
        assertFalse(inRange.contains("\"[1.5,)\""), inRange)
    }

    @Test
    @DisplayName("RESOLVED: marker override replaces the base child collection for an in-range version")
    fun resolvedMarkerReplace() {
        val c = component()
        val b = base(c) { buildSystem = "MAVEN" }
        b.dockerImages.add(docker(b, image = "base/img"))
        val m = marker(c, "[2,)", MarkerAttributes.DISTRIBUTION_DOCKER) {}
        m.dockerImages.add(docker(m, image = "v2/img"))

        val resolved = renderer.renderResolved(c, "2.5")!!
        assertTrue(resolved.contains("imageName = \"v2/img\""), resolved)
        assertFalse(resolved.contains("base/img"), resolved)
    }

    @Test
    @DisplayName("RESOLVED: null-clear of jira.hotfixVersionFormat suppresses the per-component fallback")
    fun resolvedHotfixNullClear() {
        val c = component().also { it.jiraHotfixVersionFormat = "hf/\$major" }
        base(c) { jiraProjectKey = "X" }
        // import-only null-clear: per-range SCALAR_OVERRIDE row exists with a NULL typed column
        scalarOverride(c, "[2,)", "jira.hotfixVersionFormat") { jiraHotfixVersionFormat = null }

        val inRange = renderer.renderResolved(c, "2.5")!!
        assertFalse(inRange.contains("hotfixVersionFormat"), inRange)
        assertFalse(inRange.contains("hf/\$major"), inRange)

        // Out of the override range → per-component fallback still applies.
        val outOfRange = renderer.renderResolved(c, "1.0")!!
        assertTrue(outOfRange.contains("hotfixVersionFormat = 'hf/\$major'"), outOfRange)
    }

    @Test
    @DisplayName("RESOLVED: null when no BASE row")
    fun resolvedNullWhenNoBase() {
        val noBase = component()
        marker(noBase, "[1,)", MarkerAttributes.VCS_SETTINGS) {}
        assertNull(renderer.renderResolved(noBase, "1.0"))
    }

    @Test
    @DisplayName("RESOLVED: out-of-range version still renders the base view (resolver fallback semantics)")
    fun resolvedFallsBackToBase() {
        val c = component().also { base(it) { buildSystem = "MAVEN" } }
        scalarOverride(c, "[1.5,)", "build.javaVersion") { javaVersion = "11" }
        val out = renderer.renderResolved(c, "1.0")!!
        assertTrue(out.contains("buildSystem = MAVEN"), out)
        assertFalse(out.contains("javaVersion = \"11\""), out)
    }
}
