package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.BuildConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.JiraComponentConfigEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import java.util.UUID

/**
 * SYS-040 — `ComponentEntity.toSummaryResponse()` exposes three derived
 * list-view extras: `buildSystem`, `jiraProjectKey`, `vcsPath`. All three
 * traverse nested OneToMany collections via `firstOrNull()` and must
 * return null when the source row is absent or its leaf value is blank.
 *
 * The Portal /components page consumes these to render Build System badge
 * + Jira/Git link icons without paying the cost of a per-row detail
 * fetch. Blank-vs-null parity matters because `vcsPath` is a non-nullable
 * `String = ""` on the entity (TD-002 — VCS path can be intentionally
 * empty for components that have no source tree yet).
 */
class ComponentSummaryMapperTest {
    private fun baseComponent() =
        ComponentEntity(
            id = UUID.randomUUID(),
            name = "alpha",
        )

    @Test
    @DisplayName("default entity → teamcityProjectId is null")
    fun default_teamcityProjectId_isNull() {
        assertNull(baseComponent().toSummaryResponse().teamcityProjectId)
    }

    @Test
    @DisplayName("populated teamcityProjectId propagates to summary response")
    fun teamcityProjectId_propagates() {
        val component = baseComponent().also { it.teamcityProjectId = "MyProject_Alpha" }
        assertEquals("MyProject_Alpha", component.toSummaryResponse().teamcityProjectId)
    }

    @Test
    @DisplayName("default entity → teamcityProjectUrl is null")
    fun default_teamcityProjectUrl_isNull() {
        assertNull(baseComponent().toSummaryResponse().teamcityProjectUrl)
    }

    @Test
    @DisplayName("populated teamcityProjectUrl propagates to summary response")
    fun teamcityProjectUrl_propagates() {
        val component =
            baseComponent().also {
                it.teamcityProjectUrl = "https://teamcity.example.com/project/MyProject_Alpha"
            }
        assertEquals(
            "https://teamcity.example.com/project/MyProject_Alpha",
            component.toSummaryResponse().teamcityProjectUrl,
        )
    }

    @Test
    @DisplayName("empty nested collections → all three SYS-040 fields null, labels empty")
    fun emptyNested_allNull() {
        val response = baseComponent().toSummaryResponse()

        assertNull(response.buildSystem)
        assertNull(response.jiraProjectKey)
        assertNull(response.vcsPath)
        assertTrue(response.labels.isEmpty())
    }

    @Test
    @DisplayName("labels are mapped from entity array to List<String>")
    fun labels_mappedFromEntityArray() {
        val component = baseComponent()
        component.labels = arrayOf("backend", "core")

        val response = component.toSummaryResponse()

        assertEquals(listOf("backend", "core"), response.labels)
    }

    @Test
    @DisplayName("populated nested → all three SYS-040 fields propagate from first row")
    fun populatedNested_propagatesFromFirstRow() {
        val component = baseComponent()
        component.buildConfigurations.add(
            BuildConfigurationEntity(component = component, buildSystem = "GRADLE"),
        )
        component.jiraComponentConfigs.add(
            JiraComponentConfigEntity(component = component, projectKey = "PROJ"),
        )
        val vcs = VcsSettingsEntity(component = component)
        vcs.entries.add(VcsSettingsEntryEntity(vcsSettings = vcs, vcsPath = "org/repo"))
        component.vcsSettings.add(vcs)

        val response = component.toSummaryResponse()

        assertEquals("GRADLE", response.buildSystem)
        assertEquals("PROJ", response.jiraProjectKey)
        assertEquals("org/repo", response.vcsPath)
    }

    @Test
    @DisplayName("blank leaf values → normalized to null (Portal treats absence and empty alike)")
    fun blankLeaves_normalizedToNull() {
        val component = baseComponent()
        component.buildConfigurations.add(
            BuildConfigurationEntity(component = component, buildSystem = "  "),
        )
        component.jiraComponentConfigs.add(
            JiraComponentConfigEntity(component = component, projectKey = ""),
        )
        // vcsPath is non-nullable on the entity; the empty default is the
        // case the takeIf { isNotBlank() } guard exists to handle.
        val vcs = VcsSettingsEntity(component = component)
        vcs.entries.add(VcsSettingsEntryEntity(vcsSettings = vcs))
        component.vcsSettings.add(vcs)

        val response = component.toSummaryResponse()

        assertNull(response.buildSystem)
        assertNull(response.jiraProjectKey)
        assertNull(response.vcsPath)
    }

    @Test
    @DisplayName("vcsSettings row with empty entries → vcsPath is null (no NPE)")
    fun vcsSettingsRowWithEmptyEntries_vcsPathNull() {
        val component = baseComponent()
        // Distinct from emptyNested_allNull: parent VcsSettingsEntity row exists
        // (vcsType=SINGLE per TD-002), but its OneToMany entries collection is
        // empty. The mapper's chain `.entries.firstOrNull()` must return null
        // safely.
        component.vcsSettings.add(VcsSettingsEntity(component = component))

        val response = component.toSummaryResponse()

        assertNull(response.vcsPath)
    }

    @Test
    @DisplayName("multiple nested rows → first-row deterministic pick (insertion order)")
    fun multipleNested_picksFirst() {
        val component = baseComponent()
        component.buildConfigurations.add(
            BuildConfigurationEntity(component = component, buildSystem = "GRADLE"),
        )
        component.buildConfigurations.add(
            BuildConfigurationEntity(component = component, buildSystem = "MAVEN"),
        )

        val response = component.toSummaryResponse()

        assertEquals("GRADLE", response.buildSystem)
    }
}
