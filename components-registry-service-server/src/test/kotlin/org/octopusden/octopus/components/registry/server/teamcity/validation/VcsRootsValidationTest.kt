package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.TeamcityValidationRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.teamcity.placement.FakeEnrichedTcProjectFetcher
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementDiffRowStatus
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementEditHistory
import org.octopusden.octopus.components.registry.server.teamcity.placement.TcCompileConfig
import org.octopusden.octopus.components.registry.server.teamcity.placement.TcVcsRootEntry
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffService
import org.octopusden.octopus.components.registry.server.teamcity.placement.compileBuildType
import org.octopusden.octopus.components.registry.server.teamcity.placement.project
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject as ExternalTeamcityProject

class VcsRootsValidationTest {
    private val app = "ssh://h/prj/app-one.git"
    private val tooling = "ssh://h/tools/shared-tooling.git"
    private val appB = "ssh://h/prj/app-two.git"

    private fun config(
        id: String,
        vararg urls: String,
    ) = TcCompileConfig(id, urls.map { TcVcsRootEntry(it, "") }, null)

    @Test
    fun `an extra root is a WARNING naming the repository and the build type (RED)`() {
        val result = VcsRootsValidation.check("comp-one", listOf(app), listOf(config("bt1", app, tooling)))

        assertNotNull(result)
        assertEquals(Status.WARNING, result!!.status)
        assertTrue(result.message!!.contains("tools/shared-tooling"))
        assertTrue(result.message!!.contains("bt1"))
    }

    @Test
    fun `a missing root is a WARNING naming the repository (RED)`() {
        val result = VcsRootsValidation.check("comp-one", listOf(app, tooling), listOf(config("bt1", app)))

        assertEquals(Status.WARNING, result?.status)
        assertTrue(result!!.message!!.contains("tools/shared-tooling"))
    }

    @Test
    fun `matching roots produce no finding`() {
        assertNull(VcsRootsValidation.check("comp-one", listOf(app), listOf(config("bt1", app))))
    }

    private fun comp(key: String) = ComponentEntity(id = UUID.randomUUID(), componentKey = key)

    private fun baseRow(
        component: ComponentEntity,
        vararg paths: String,
    ): ComponentConfigurationEntity {
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = component, rowType = "BASE")
        paths.forEachIndexed { i, p ->
            row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "r$i", vcsPath = p, sortOrder = i)
        }
        return row
    }

    private fun line(
        component: ComponentEntity,
        projectId: String,
    ) = VersionLineEntity(component = component, teamcityProject = TeamcityProjectEntity(projectId = projectId))

    private fun fetcher(projects: Map<String, ExternalTeamcityProject>) = FakeEnrichedTcProjectFetcher(projects)

    /** Runs the validation service over the fixture; returns the stored VCS-roots findings by project id. */
    private fun validationFindings(
        rows: List<ComponentConfigurationEntity>,
        lines: List<VersionLineEntity>,
        projects: Map<String, ExternalTeamcityProject>,
    ): Map<String, String?> {
        val configRepo = mock<ComponentConfigurationRepository>()
        whenever(configRepo.findAllRowsWithVcsEntries()).thenReturn(rows)
        val versionLines = mock<VersionLineRepository>()
        whenever(versionLines.findDistinctLinkedProjectIds()).thenReturn(lines.map { it.teamcityProject.projectId }.distinct())
        whenever(versionLines.findByProjectIdsWithComponent(any())).thenReturn(lines)
        val validations = mock<TeamcityValidationRepository>()
        whenever(validations.findDistinctStoredProjectIds()).thenReturn(emptyList())
        val tx = mock<TransactionTemplate>()
        whenever(tx.executeWithoutResult(any())).thenAnswer { (it.arguments[0] as java.util.function.Consumer<Any?>).accept(null) }
        val catalog = object : TemplateCatalog {
            override val gradleBuildTemplateId = "CDGradleBuild"
            override val mavenBuildTemplateId = "CDJavaMavenBuild"
            override val releaseFamilyTemplateIds = emptySet<String>()

            override fun defaultBuildStepId(templateId: String): String? = null
        }
        val service =
            TeamcityValidationService(versionLines, configRepo, validations, fetcher(projects), TeamcityProjectMapper(), catalog, tx)

        service.validate()

        val saved = argumentCaptor<List<TeamcityValidationEntity>>()
        verify(validations, atLeast(0)).saveAll(saved.capture())
        return saved.allValues
            .flatten()
            .filter { it.type == "VCS_ROOTS_DIFFER_FROM_REGISTRY" }
            .associate { it.projectId to it.message }
    }

    private fun diffStatuses(
        rows: List<ComponentConfigurationEntity>,
        projectIdsByComponent: Map<UUID, List<String>>,
        projects: Map<String, ExternalTeamcityProject>,
    ): Map<String, PlacementDiffRowStatus> {
        val configRepo = mock<ComponentConfigurationRepository>()
        whenever(configRepo.findAllRowsWithVcsEntries()).thenReturn(rows)
        val versionLines = mock<VersionLineRepository>()
        projectIdsByComponent.forEach { (id, ps) -> whenever(versionLines.findDistinctTeamcityProjectIdsByComponentId(id)).thenReturn(ps) }
        val diff = TeamcityPlacementDiffService(configRepo, versionLines, fetcher(projects), mock<PlacementEditHistory>())
        return diff.runDiff().rows.associate { it.componentKey to it.status }
    }

    @Test
    fun `the service stores the finding for a project whose compile configuration attaches an extra root`() {
        val a = comp("comp-one")
        val findings = validationFindings(
            listOf(baseRow(a, app)),
            listOf(line(a, "P")),
            mapOf("P" to project(compileBuildType("bt1", roots = listOf(app to "", tooling to "")))),
        )

        assertTrue(findings.getValue("P")!!.contains("comp-one"))
        assertTrue(findings.getValue("P")!!.contains("tools/shared-tooling"))
    }

    @Test
    fun `components sharing a project with the same roots get no finding (RED)`() {
        val a = comp("comp-one")
        val b = comp("comp-two")
        val findings = validationFindings(
            listOf(baseRow(a, app), baseRow(b, app)),
            listOf(line(a, "P"), line(b, "P")),
            mapOf("P" to project(compileBuildType("bt1", roots = listOf(app to "")))),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a shared project is judged per component, like the Diff (RED)`() {
        val a = comp("comp-one")
        val b = comp("comp-two")
        val rows = listOf(baseRow(a, app), baseRow(b, appB))
        val projects = mapOf(
            "P" to project(compileBuildType("bt1", roots = listOf(app to "")), compileBuildType("bt2", roots = listOf(appB to ""))),
        )

        val findings = validationFindings(rows, listOf(line(a, "P"), line(b, "P")), projects)
        val statuses = diffStatuses(rows, mapOf(a.id!! to listOf("P"), b.id!! to listOf("P")), projects)

        assertEquals(PlacementDiffRowStatus.ROOTS_MISMATCH, statuses["comp-one"])
        assertEquals(PlacementDiffRowStatus.ROOTS_MISMATCH, statuses["comp-two"])
        val message = findings.getValue("P")!!
        assertTrue(message.contains("comp-one") && message.contains("comp-two"))
    }

    @Test
    fun `a component spanning two projects has no false missing root, like the Diff (RED)`() {
        val a = comp("comp-one")
        val rows = listOf(baseRow(a, app, appB))
        val split = mapOf(
            "P1" to project(compileBuildType("bt1", roots = listOf(app to ""))),
            "P2" to project(compileBuildType("bt2", roots = listOf(appB to ""))),
        )

        val findings = validationFindings(rows, listOf(line(a, "P1"), line(a, "P2")), split)
        val statuses = diffStatuses(rows, mapOf(a.id!! to listOf("P1", "P2")), split)

        assertTrue(findings.isEmpty())
        assertTrue(statuses["comp-one"] != PlacementDiffRowStatus.ROOTS_MISMATCH)
    }
}
