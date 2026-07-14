package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingTokenEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity
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

    private fun component(key: String = "bcomponent"): ComponentEntity = ComponentEntity(id = UUID.randomUUID(), componentKey = key)

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

    private fun ownership(
        c: ComponentEntity,
        group: String,
        mode: ArtifactIdMode,
        range: String = "(,0),[0,)",
        tokens: List<String> = emptyList(),
        order: Int = 0,
    ): ComponentArtifactMappingEntity {
        val m =
            ComponentArtifactMappingEntity(
                id = UUID.randomUUID(),
                component = c,
                versionRange = range,
                groupPattern = group,
                artifactIdMode = mode.name,
                sortOrder = order,
            )
        tokens.forEachIndexed { i, t ->
            m.tokens.add(ComponentArtifactMappingTokenEntity(id = UUID.randomUUID(), mapping = m, artifactPattern = t, sortOrder = i))
        }
        c.artifactMappings.add(m)
        return m
    }

    // ----------------------------------------------------------------------
    // Ownership (artifactIds) rendering — modes, per-range override, ALL_EXCEPT export
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("FULL: base ALL ownership renders an artifactIds block with the catch-all pattern")
    fun fullOwnershipBaseAll() {
        val c = component()
        base(c) { buildSystem = "MAVEN" }
        ownership(c, "com.example.foo", ArtifactIdMode.ALL)
        val out = renderer.renderFull(c)
        assertTrue(out.contains("artifactIds {"), out)
        assertTrue(out.contains("groupPattern = \"com.example.foo\""), out)
        // CodeBuilder double-escapes backslashes in the Groovy string literal.
        assertTrue(out.contains("artifactPattern = \"[\\\\w-\\\\.]+\""), out)
    }

    @Test
    @DisplayName("FULL: an ownership-only per-range override still emits its range block (P1-C)")
    fun fullOwnershipOnlyOverrideRangeBlock() {
        val c = component()
        base(c) { buildSystem = "MAVEN" }
        ownership(c, "com.example.foo", ArtifactIdMode.ALL)
        // A range whose ONLY override is artifact ownership — no scalar/marker config row for it.
        ownership(c, "com.example.foo", ArtifactIdMode.EXPLICIT, range = "[1.0,2.0)", tokens = listOf("foo-service"))
        val out = renderer.renderFull(c)
        assertTrue(out.contains("\"[1.0,2.0)\" {"), out)
        assertTrue(out.contains("artifactPattern = \"foo-service\""), out)
    }

    @Test
    @DisplayName("FULL: ALL_EXCEPT_CLAIMED renders the sibling-aware lookahead from the export-pattern map (P1-B)")
    fun fullOwnershipAllExceptExportLookahead() {
        val c = component()
        base(c) { buildSystem = "MAVEN" }
        val m = ownership(c, "com.example.foo", ArtifactIdMode.ALL_EXCEPT_CLAIMED)
        val out = renderer.renderFull(c, mapOf(m.id!! to "(?!(?:foo-legacy)\$)[\\w-\\.]+"))
        // Contains '$' ⇒ rendered single-quoted; backslashes are doubled.
        assertTrue(out.contains("artifactPattern = '(?!(?:foo-legacy)\$)[\\\\w-\\\\.]+'"), out)
        // Without the export map it falls back to the wire catch-all (double-quoted).
        assertTrue(renderer.renderFull(c).contains("artifactPattern = \"[\\\\w-\\\\.]+\""))
    }

    // ----------------------------------------------------------------------
    // ARTGRP: artifact-group canonicalization — view-as-code re-composes the
    // contiguous split-equivalent rows of a normalized (one-groupId-per-row)
    // ownership into a SINGLE `artifact { }` block per legacy pair, so as-code
    // stays byte-stable against the legacy DSL. Genuinely-distinct same-range
    // mappings (different tokens) stay separate blocks.
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("ARTGRP-AC-001: two split EXPLICIT group rows render as ONE re-composed artifact block")
    fun `ARTGRP-AC-001 split rows render one recomposed artifact block`() {
        val c = component()
        base(c) { buildSystem = "MAVEN" }
        // Post-canonicalization storage: one row per groupId, identical token, contiguous sortOrder.
        ownership(c, "grp-alfa", ArtifactIdMode.EXPLICIT, tokens = listOf("widget"), order = 0)
        ownership(c, "grp-beta", ArtifactIdMode.EXPLICIT, tokens = listOf("widget"), order = 1)
        val out = renderer.renderFull(c)
        assertEquals(
            1,
            Regex("artifact \\{").findAll(out).count(),
            "split rows must re-compose to ONE artifact block, not two:\n$out",
        )
        assertTrue(out.contains("groupPattern = \"grp-alfa,grp-beta\""), out)
        assertTrue(out.contains("artifactPattern = \"widget\""), out)
    }

    @Test
    @DisplayName("ARTGRP-AC-002 (no over-join): same-range rows with DIFFERENT tokens stay TWO artifact blocks")
    fun `ARTGRP-AC-002 heterogeneous rows stay separate blocks`() {
        val c = component()
        base(c) { buildSystem = "MAVEN" }
        ownership(c, "grp-alfa", ArtifactIdMode.EXPLICIT, tokens = listOf("widget-x"), order = 0)
        ownership(c, "grp-beta", ArtifactIdMode.EXPLICIT, tokens = listOf("widget-y"), order = 1)
        val out = renderer.renderFull(c)
        assertEquals(
            2,
            Regex("artifact \\{").findAll(out).count(),
            "distinct-token rows are not split-equivalent → must stay two blocks:\n$out",
        )
    }

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
            jiraMinorVersionFormat = "\$major"
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
    @DisplayName("FULL: RANGE_PRESENCE-only range renders as an explicit empty block (coverage is faithful — ADR-018)")
    fun fullRangePresenceRendersEmptyBlock() {
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
        // Decoupled model: the ALL_VERSIONS base carries the values; the RANGE_PRESENCE row is the
        // SOLE record that the component is supported on [9,10). It must surface so as-code does not
        // misrepresent a bounded-coverage component as all-versions.
        assertTrue(out.contains("\"[9,10)\""), out)
    }

    @Test
    @DisplayName("FULL: a declared empty coverage block on a defaulted component is faithful (M2 shape)")
    fun fullEmptyCoverageBlockIsFaithful() {
        // build{jV=17} top-level (→ ALL_VERSIONS base) + supported only from [1.0,) (empty block,
        // a RANGE_PRESENCE row). as-code must show the [1.0,) coverage block, not look all-versions.
        val c = component()
        base(c) {
            buildSystem = "MAVEN"
            javaVersion = "17"
        }
        c.configurations.add(
            ComponentConfigurationEntity(
                id = UUID.randomUUID(),
                component = c,
                versionRange = "[1.0,)",
                overriddenAttribute = null,
                rowType = "RANGE_PRESENCE",
            ),
        )
        val out = renderer.renderFull(c)
        assertTrue(out.contains("\"[1.0,)\""), "as-code must surface the [1.0,) supported-coverage block: $out")
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
    @DisplayName("FULL: skipCommitCheck renders the legacy DSL literal externalRegistry = \"NOT_AVAILABLE\"")
    fun fullSkipCommitCheckRendersSentinel() {
        val c = component().also { it.skipCommitCheck = true }
        base(c) { buildSystem = "MAVEN" }

        val out = renderer.renderFull(c)
        assertTrue(out.contains("vcsSettings {"), out)
        assertTrue(out.contains("externalRegistry = \"NOT_AVAILABLE\""), out)
    }

    @Test
    @DisplayName("FULL: skipCommitCheck wins over a real registry — renders NOT_AVAILABLE, not the real value")
    fun fullSkipCommitCheckWinsOverRealRegistry() {
        val c = component().also {
            it.skipCommitCheck = true
            it.vcsExternalRegistry = "some-registry"
        }
        base(c) { buildSystem = "MAVEN" }

        val out = renderer.renderFull(c)
        assertTrue(out.contains("externalRegistry = \"NOT_AVAILABLE\""), out)
        assertFalse(out.contains("some-registry"), out)
    }

    @Test
    @DisplayName("FULL: a real registry with the flag off renders the real value verbatim")
    fun fullRealRegistryNoFlag() {
        val c = component().also { it.vcsExternalRegistry = "some-registry" }
        base(c) { buildSystem = "MAVEN" }

        val out = renderer.renderFull(c)
        assertTrue(out.contains("externalRegistry = \"some-registry\""), out)
        assertFalse(out.contains("NOT_AVAILABLE"), out)
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
    @DisplayName("FULL: per-range distribution block renders markers but NOT the per-component fields (CRS #387)")
    fun fullPerRangeDistributionOmitsPerComponentFields() {
        val c = component()
        c.distributionExplicit = true
        c.distributionExternal = true
        c.securityGroups.add(DistributionSecurityGroupEntity(component = c, groupType = "read", groupName = "grp-a"))
        base(c) { buildSystem = "MAVEN" }
        val m = marker(c, "[2,)", MarkerAttributes.DISTRIBUTION_DOCKER) {}
        m.dockerImages.add(docker(m, image = "acme/svc", flavor = "slim"))

        val out = renderer.renderFull(c)
        // Base distribution carries the per-component fields.
        assertTrue(out.contains("explicit = true"), out)
        assertTrue(out.contains("external = true"), out)
        assertTrue(out.contains("securityGroups {"), out)
        // The per-range block renders only the docker marker — never the per-component
        // fields (explicit / external / securityGroups.read), which would leak per-range.
        val rangeBlock = out.substringAfter("\"[2,)\" {")
        assertTrue(rangeBlock.contains("docker {"), rangeBlock)
        assertFalse(rangeBlock.contains("explicit"), rangeBlock)
        assertFalse(rangeBlock.contains("external"), rangeBlock)
        assertFalse(rangeBlock.contains("securityGroups"), rangeBlock)
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
    @DisplayName("FULL: synthetic base still renders its base row aspects (they are the fallback for all versions)")
    fun fullSyntheticBaseRendersBaseAspects() {
        // A "synthetic" base (no explicit all-versions DSL block) still carries the
        // real shared values the Groovy loader merged in from top-level fields, and
        // those values resolve as the fallback for every version — so they MUST show
        // at the top level, not be hidden behind the overriding ranges.
        val c = component()
        base(c, synthetic = true) {
            buildSystem = "MAVEN"
            jiraProjectKey = "BASE"
        }
        scalarOverride(c, "[1,2)", "jira.projectKey") { jiraProjectKey = "AAA" }

        val out = renderer.renderFull(c)
        assertTrue(out.contains("buildSystem = MAVEN"), "base build aspect must render:\n$out")
        assertTrue(out.contains("projectKey = \"BASE\""), "base jira aspect must render:\n$out")
        assertTrue(out.contains("\"[1,2)\" {"), out)
        assertTrue(out.contains("projectKey = \"AAA\""), out)
    }

    @Test
    @DisplayName("FULL: synthetic-base VCS renders at top level, not only in the overriding range")
    fun fullSyntheticBaseVcsRendersAtTopLevel() {
        // Repro of the reported bug: a component whose top-level vcsUrl/tag is merged
        // onto a synthetic base, with a later range overriding the VCS (e.g. a new
        // tag). The base VCS applies to the earlier versions and must render at the
        // top level — previously it was suppressed and looked range-only.
        val c = component()
        val b = base(c, synthetic = true) {}
        b.vcsEntries.add(vcs(b, name = "main", path = "ssh://git/repo.git", branch = "master"))
        val m = marker(c, "[1.0.107,)", MarkerAttributes.VCS_SETTINGS) {}
        m.vcsEntries.add(vcs(m, name = "main", path = "ssh://git/repo.git", branch = "release"))

        val out = renderer.renderFull(c)
        val rangeHeaderIdx = out.indexOf("\"[1.0.107,)\" {")
        val baseVcsIdx = out.indexOf("vcsSettings {")
        val baseBranchIdx = out.indexOf("branch = \"master\"")
        assertTrue(rangeHeaderIdx >= 0, out)
        // A top-level vcsSettings block opens before the range block (structural)…
        assertTrue(
            baseVcsIdx in 0 until rangeHeaderIdx,
            "a top-level vcsSettings block must open before the range block:\n$out",
        )
        // …and it carries the base branch (the override's branch lives in the range block).
        assertTrue(
            baseBranchIdx in 0 until rangeHeaderIdx,
            "base vcsSettings must render at the top level, before the range block:\n$out",
        )
        assertTrue(out.contains("branch = \"release\""), "range vcs override must still render:\n$out")
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

    // ----------------------------------------------------------------------
    // Bounded base row (versionRange != ALL_VERSIONS)
    // ----------------------------------------------------------------------

    /** A BASE row scoped to a bounded range instead of the default ALL_VERSIONS. */
    private fun boundedBase(
        c: ComponentEntity,
        range: String,
        block: ComponentConfigurationEntity.() -> Unit = {},
    ): ComponentConfigurationEntity {
        val row =
            ComponentConfigurationEntity(
                id = UUID.randomUUID(),
                component = c,
                versionRange = range,
                overriddenAttribute = null,
                rowType = "BASE",
                isSyntheticBase = false,
            ).apply(block)
        c.configurations.add(row)
        return row
    }

    @Test
    @DisplayName("FULL: bounded base range is surfaced as an explicit (empty) block")
    fun fullEmitsBoundedBaseRangeBlock() {
        val c = component()
        boundedBase(c, "[1.0.700,)") {
            buildSystem = "MAVEN"
            javaVersion = "21"
            jiraProjectKey = "X"
        }
        val out = renderer.renderFull(c)
        // The component-level defaults still render in the top-level body...
        assertTrue(out.contains("javaVersion = \"21\""), out)
        // ...and the bounded base range is shown as its own block so it's clear the component
        // is supported ONLY within it. It is empty (all fields inherited from the body above).
        assertTrue(out.contains("\"[1.0.700,)\" {"), out)
    }

    @Test
    @DisplayName("RESOLVED: version below the bounded base range returns null (range gate, mirrors v2 404)")
    fun resolvedNullWhenBelowBoundedBaseRange() {
        val c = component()
        boundedBase(c, "[1.0.700,)") {
            buildSystem = "MAVEN"
            javaVersion = "21"
            jiraProjectKey = "X"
        }
        // 1.0.1 is below the only declared range → no config resolves → null (404), matching
        // EntityMappers.toResolvedEscrowModuleConfig and the real v2 resolver.
        assertNull(renderer.renderResolved(c, "1.0.1"))
        // In-range still resolves.
        val inRange = renderer.renderResolved(c, "1.0.700")
        assertTrue(inRange != null && inRange.contains("javaVersion = \"21\""), "$inRange")
    }
}
