package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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
import org.octopusden.octopus.components.registry.server.teamcity.placement.TcCompileConfig
import org.octopusden.octopus.components.registry.server.teamcity.placement.TcVcsRootEntry
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

    private fun config(
        id: String,
        vararg urls: String,
    ) = TcCompileConfig(id, urls.map { TcVcsRootEntry(it, "") }, null)

    @Test
    fun `an extra root is a WARNING naming the repository and the build type (RED)`() {
        val result = VcsRootsValidation.check(listOf(app), listOf(config("bt1", app, tooling)))

        assertNotNull(result)
        assertEquals(Status.WARNING, result!!.status)
        assertTrue(result.message!!.contains("tools/shared-tooling"))
        assertTrue(result.message!!.contains("bt1"))
    }

    @Test
    fun `a missing root is a WARNING naming the repository (RED)`() {
        val result = VcsRootsValidation.check(listOf(app, tooling), listOf(config("bt1", app)))

        assertEquals(Status.WARNING, result?.status)
        assertTrue(result!!.message!!.contains("tools/shared-tooling"))
    }

    @Test
    fun `matching roots produce no finding`() {
        assertNull(VcsRootsValidation.check(listOf(app), listOf(config("bt1", app))))
    }

    @Test
    fun `the service stores the finding for a project whose compile configuration attaches an extra root (RED)`() {
        val comp = ComponentEntity(id = UUID.randomUUID(), componentKey = "comp-one")
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = comp, rowType = "BASE")
        row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "main", vcsPath = app, sortOrder = 0)
        val configRepo = mock<ComponentConfigurationRepository>()
        whenever(configRepo.findAllRowsWithVcsEntries()).thenReturn(listOf(row))
        val versionLines = mock<VersionLineRepository>()
        whenever(versionLines.findDistinctLinkedProjectIds()).thenReturn(listOf("P"))
        whenever(versionLines.findByProjectIdsWithComponent(any())).thenReturn(
            listOf(VersionLineEntity(component = comp, teamcityProject = TeamcityProjectEntity(projectId = "P"))),
        )
        val validations = mock<TeamcityValidationRepository>()
        whenever(validations.findDistinctStoredProjectIds()).thenReturn(emptyList())
        val tx = mock<TransactionTemplate>()
        whenever(tx.executeWithoutResult(any())).thenAnswer { (it.arguments[0] as java.util.function.Consumer<Any?>).accept(null) }
        val fetcher = object : EnrichedTcProjectFetcher {
            override fun fetch(projectId: String): ExternalTeamcityProject =
                project(compileBuildType("bt1", roots = listOf(app to "", tooling to "")))

            override fun invalidateAll() = Unit
        }
        val catalog = object : TemplateCatalog {
            override val gradleBuildTemplateId = "CDGradleBuild"
            override val mavenBuildTemplateId = "CDJavaMavenBuild"
            override val releaseFamilyTemplateIds = emptySet<String>()

            override fun defaultBuildStepId(templateId: String): String? = null
        }
        val service = TeamcityValidationService(versionLines, configRepo, validations, fetcher, TeamcityProjectMapper(), catalog, tx)

        service.validate()

        val saved = argumentCaptor<List<TeamcityValidationEntity>>()
        verify(validations).saveAll(saved.capture())
        val finding = saved.firstValue.single { it.type == "VCS_ROOTS_DIFFER_FROM_REGISTRY" }
        assertEquals("WARNING", finding.status)
    }
}
