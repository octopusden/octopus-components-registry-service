package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentSystemEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import java.util.UUID

/**
 * Phase 6 — `ComponentEntity.toSummaryResponse()` rewritten against the v2
 * entity graph and v4 DTOs.
 *
 * SYS-040 fields (`buildSystem`, `jiraProjectKey`, `vcsPath`,
 * `teamcityProjectId`, `teamcityProjectUrl`) are derived from the BASE
 * configuration row (`overriddenAttribute IS NULL`) and the first child
 * (`sort_order = 0`). Blank strings → null. Empty/missing entities → null.
 *
 * In v2 the legacy entity chains (`BuildConfigurationEntity`,
 * `JiraComponentConfigEntity`, `VcsSettingsEntity → VcsSettingsEntryEntity`)
 * are gone. The scalar values live directly on `ComponentConfigurationEntity`
 * (base row) and vcs entries are direct children of the config row.
 */
class ComponentSummaryMapperTest {
    // -----------------------------------------------------------------------
    // Fixture builders
    // -----------------------------------------------------------------------

    private fun minimalComponent(key: String = "alpha"): ComponentEntity =
        ComponentEntity(
            id = UUID.randomUUID(),
            componentKey = key,
        )

    private fun baseConfigFor(component: ComponentEntity): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "(,0),[0,)",
            overriddenAttribute = null, // BASE row
            rowType = "BASE",
        )

    // -----------------------------------------------------------------------
    // Top-level scalar fields
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("componentKey → name in summary response")
    fun componentKey_mapsToName() {
        val component = minimalComponent("my-service")
        assertEquals("my-service", component.toSummaryResponse().name)
    }

    @Test
    @DisplayName("default entity → all SYS-040 fields null, labels empty, system null")
    fun defaultEntity_allSys040FieldsNull() {
        val response = minimalComponent().toSummaryResponse()

        assertNull(response.buildSystem)
        assertNull(response.javaVersion)
        assertNull(response.jiraProjectKey)
        assertNull(response.vcsPath)
        assertNull(response.teamcityProjectId)
        assertNull(response.teamcityProjectUrl)
        assertTrue(response.labels.isEmpty())
        assertTrue(response.systems.isEmpty())
    }

    // -----------------------------------------------------------------------
    // systemJunctions → systems: Set<String> (multi-value)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("entity systemJunctions → multi-value `systems` set in summary response")
    fun systemJunctions_mapsToSystemsSet() {
        val component = minimalComponent()
        val id = component.id!!
        component.systemJunctions.add(ComponentSystemEntity(componentId = id, systemCode = "SYS_A"))
        component.systemJunctions.add(ComponentSystemEntity(componentId = id, systemCode = "SYS_B"))
        assertEquals(setOf("SYS_A", "SYS_B"), component.toSummaryResponse().systems)
    }

    // -----------------------------------------------------------------------
    // labelJunctions → labels: List<String>
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("labels mapped from labelJunctions to List<String>")
    fun labels_mappedFromLabelJunctions() {
        val component = minimalComponent()
        val id = component.id!!
        component.labelJunctions.add(ComponentLabelEntity(componentId = id, labelCode = "backend"))
        component.labelJunctions.add(ComponentLabelEntity(componentId = id, labelCode = "core"))

        assertEquals(listOf("backend", "core"), component.toSummaryResponse().labels)
    }

    // -----------------------------------------------------------------------
    // buildSystem from BASE config row
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BASE config buildSystem propagates to summary")
    fun baseConfig_buildSystem_propagates() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component).also { it.buildSystem = "GRADLE" }
        component.configurations.add(cfg)

        assertEquals("GRADLE", component.toSummaryResponse().buildSystem)
    }

    @Test
    @DisplayName("BASE config with blank buildSystem → null in summary (blank-to-null)")
    fun baseConfig_blankBuildSystem_normalizedToNull() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component).also { it.buildSystem = "   " }
        component.configurations.add(cfg)

        assertNull(component.toSummaryResponse().buildSystem)
    }

    @Test
    @DisplayName("no BASE config (only SCALAR_OVERRIDE) → buildSystem null AND javaVersion null (summary reads BASE only)")
    fun noBaseConfig_buildSystemNull() {
        val component = minimalComponent()
        val override = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[2,3)",
            overriddenAttribute = "build.javaVersion",
            rowType = "SCALAR_OVERRIDE",
            javaVersion = "21",
        )
        component.configurations.add(override)

        val response = component.toSummaryResponse()
        assertNull(response.buildSystem)
        // javaVersion is genuinely overridable — this SCALAR_OVERRIDE row carries
        // javaVersion="21", but the summary must NOT surface it: the projection
        // reads the BASE row only, so an override-only value stays absent.
        assertNull(response.javaVersion)
    }

    // -----------------------------------------------------------------------
    // javaVersion from BASE config row (same shape as buildSystem)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BASE config javaVersion propagates to summary")
    fun baseConfig_javaVersion_propagates() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component).also { it.javaVersion = "21" }
        component.configurations.add(cfg)

        assertEquals("21", component.toSummaryResponse().javaVersion)
    }

    @Test
    @DisplayName("BASE config with blank javaVersion → null in summary (blank-to-null)")
    fun baseConfig_blankJavaVersion_normalizedToNull() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component).also { it.javaVersion = "   " }
        component.configurations.add(cfg)

        assertNull(component.toSummaryResponse().javaVersion)
    }

    // -----------------------------------------------------------------------
    // jiraProjectKey from BASE config row
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BASE config jiraProjectKey propagates to summary")
    fun baseConfig_jiraProjectKey_propagates() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component).also { it.jiraProjectKey = "PROJ" }
        component.configurations.add(cfg)

        assertEquals("PROJ", component.toSummaryResponse().jiraProjectKey)
    }

    @Test
    @DisplayName("BASE config with blank jiraProjectKey → null in summary")
    fun baseConfig_blankJiraProjectKey_normalizedToNull() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component).also { it.jiraProjectKey = "" }
        component.configurations.add(cfg)

        assertNull(component.toSummaryResponse().jiraProjectKey)
    }

    // -----------------------------------------------------------------------
    // vcsPath from BASE config's first vcs entry (sort_order = 0)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BASE config with vcs entry → vcsPath in summary")
    fun baseConfig_vcsEntry_vcsPathPropagates() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component)
        cfg.vcsEntries.add(
            VcsSettingsEntryEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                name = "main",
                vcsPath = "org/repo",
                sortOrder = 0,
            ),
        )
        component.configurations.add(cfg)

        assertEquals("org/repo", component.toSummaryResponse().vcsPath)
    }

    @Test
    @DisplayName("BASE config with blank vcsPath → null in summary (blank-to-null)")
    fun baseConfig_blankVcsPath_normalizedToNull() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component)
        cfg.vcsEntries.add(
            VcsSettingsEntryEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                name = "main",
                vcsPath = "",
                sortOrder = 0,
            ),
        )
        component.configurations.add(cfg)

        assertNull(component.toSummaryResponse().vcsPath)
    }

    @Test
    @DisplayName("BASE config with no vcs entries → vcsPath null (no NPE)")
    fun baseConfig_noVcsEntries_vcsPathNull() {
        val component = minimalComponent()
        component.configurations.add(baseConfigFor(component))

        assertNull(component.toSummaryResponse().vcsPath)
    }

    @Test
    @DisplayName("multiple vcs entries → entry with lowest sortOrder is picked")
    fun multipleVcsEntries_firstBySortOrderPicked() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component)
        cfg.vcsEntries.add(
            VcsSettingsEntryEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                name = "main",
                vcsPath = "org/repo-b",
                sortOrder = 2,
            ),
        )
        cfg.vcsEntries.add(
            VcsSettingsEntryEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                name = "main",
                vcsPath = "org/repo-a",
                sortOrder = 1,
            ),
        )
        component.configurations.add(cfg)

        assertEquals("org/repo-a", component.toSummaryResponse().vcsPath)
    }

    @Test
    @DisplayName("SSH vcsPath → toSummaryResponse normalises to project/repo via sshUrlToProjectRepo")
    fun sshUrlInVcsEntry_normalizedToProjectRepo() {
        val component = minimalComponent()
        val cfg = baseConfigFor(component)
        cfg.vcsEntries.add(
            VcsSettingsEntryEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                name = "main",
                vcsPath = "ssh://git@bitbucket.spb.example.com/neo/access-contol.git",
                sortOrder = 0,
            ),
        )
        component.configurations.add(cfg)

        assertEquals("neo/access-contol", component.toSummaryResponse().vcsPath)
    }

    // -----------------------------------------------------------------------
    // teamcityProjectId and teamcityProjectUrl from component_teamcity_projects
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("no TC projects → teamcityProjectId and teamcityProjectUrl are null")
    fun noTcProjects_teamcityFieldsNull() {
        val response = minimalComponent().toSummaryResponse()
        assertNull(response.teamcityProjectId)
        assertNull(response.teamcityProjectUrl)
    }

    @Test
    @DisplayName("TC project propagates id and computed URL to summary")
    fun tcProject_propagatesIdAndUrl() {
        val component = minimalComponent()
        component.versionLines.add(
            VersionLineEntity(
                id = UUID.randomUUID(),
                component = component,
                teamcityProject = TeamcityProjectEntity(id = UUID.randomUUID(), projectId = "MyProject_Alpha"),
            ),
        )

        val response = component.toSummaryResponse(teamcityBaseUrl = "https://tc.example.com")
        assertEquals("MyProject_Alpha", response.teamcityProjectId)
        assertEquals("https://tc.example.com/project/MyProject_Alpha", response.teamcityProjectUrl)
    }

    @Test
    @DisplayName("blank TC base URL → teamcityProjectUrl null even when project configured")
    fun tcProject_blankBaseUrl_urlNull() {
        val component = minimalComponent()
        component.versionLines.add(
            VersionLineEntity(
                id = UUID.randomUUID(),
                component = component,
                teamcityProject = TeamcityProjectEntity(id = UUID.randomUUID(), projectId = "Proj"),
            ),
        )

        assertNull(component.toSummaryResponse(teamcityBaseUrl = "").teamcityProjectUrl)
    }

    @Test
    @DisplayName("multiple TC projects → project with lowest sortOrder is picked")
    fun multipleTcProjects_firstBySortOrderPicked() {
        val component = minimalComponent()
        component.versionLines.add(
            VersionLineEntity(
                id = UUID.randomUUID(),
                component = component,
                teamcityProject = TeamcityProjectEntity(id = UUID.randomUUID(), projectId = "Proj_B"),
            ),
        )
        component.versionLines.add(
            VersionLineEntity(
                id = UUID.randomUUID(),
                component = component,
                teamcityProject = TeamcityProjectEntity(id = UUID.randomUUID(), projectId = "Proj_A"),
            ),
        )

        assertEquals("Proj_A", component.toSummaryResponse().teamcityProjectId)
    }

    // -----------------------------------------------------------------------
    // sshUrlToProjectRepo — SSH URL normalisation (internal fun, same package)
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
    @DisplayName("sshUrlToProjectRepo normalises SSH URLs to project/repo")
    @CsvSource(
        // Bitbucket ssh:// with no port
        "ssh://git@bitbucket.example.com/neo/access-contol.git,                    neo/access-contol",
        // Bitbucket ssh:// with explicit numeric port
        "ssh://git@bitbucket.example.com:7999/neo/access-contol.git,               neo/access-contol",
        // Bitbucket ssh:// with scm/ prefix + port
        "ssh://git@bitbucket.example.com:7999/scm/project/repo.git,                project/repo",
        // GitHub SCP-over-SSH: colon introduces org name (not a port)
        "ssh://git@github.com:octopusden/octopus-rm-gradle-plugin.git,             octopusden/octopus-rm-gradle-plugin",
        // SCP-style (Gitea / Bitbucket SCP)
        "git@gitea.example.com:org/repo.git,                                       org/repo",
        // Nested group path — only last two segments are taken (group/sub/repo → sub/repo)
        "git@gitea.example.com:group/sub/repo.git,                                 sub/repo",
        // Already normalised — must be returned as-is
        "org/repo,                                                                  org/repo",
        delimiter = ',',
    )
    fun sshUrlToProjectRepo_normalisesVcsPath(
        raw: String,
        expected: String,
    ) {
        assertEquals(expected.trim(), raw.trim().sshUrlToProjectRepo())
    }

    @Test
    @DisplayName("sshUrlToProjectRepo: single-segment path after port → original returned (no misfire)")
    fun sshUrlToProjectRepo_singleSegment_returnsOriginal() {
        // "7999" is all-digits → treated as port → afterAt.substringAfter("/") = "repo.git"
        // parts = ["repo"] → size < 2 → original URL is returned, not "7999/repo"
        val raw = "ssh://git@bitbucket.example.com:7999/repo.git"
        assertEquals(raw, raw.sshUrlToProjectRepo())
    }

    @Test
    @DisplayName("sshUrlToProjectRepo: non-git SSH URLs unchanged (e.g. Mercurial ssh://)")
    fun sshUrlToProjectRepo_nonGitSshUrl_returnsOriginal() {
        val raw = "ssh://hg@mercurial.example.com/ddd/technical"
        assertEquals(raw, raw.sshUrlToProjectRepo())
    }

    @Test
    @DisplayName("sshUrlToProjectRepo: non-git SCP-style URLs unchanged (e.g. Mercurial hg@)")
    fun sshUrlToProjectRepo_nonGitScpUrl_returnsOriginal() {
        val raw = "hg@mercurial.example.com:ddd/technical"
        assertEquals(raw, raw.sshUrlToProjectRepo())
    }
}
