package org.octopusden.octopus.components.registry.server.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * SYS-086: `VersionLineRepository.findDistinctLinkedProjectIds()` must scope to TeamCity projects
 * *currently* referenced by at least one `version_line` row, excluding a project that only exists
 * in `teamcity_project` (append-only; sync never deletes an orphaned row there) with no current
 * `version_line`. This is what makes SYS-087's pruning effective: a project unlinked from every
 * component must drop out of `TeamcityValidationService`'s scope.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db-validate")
@Timeout(120)
@Tag("integration")
class VersionLineRepositoryLinkedProjectScopeIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var teamcityProjectRepository: TeamcityProjectRepository

    @Autowired
    private lateinit var versionLineRepository: VersionLineRepository

    @Test
    @DisplayName(
        "SYS-086 findDistinctLinkedProjectIds includes a project with a current version_line row " +
            "and excludes a teamcity_project-only orphan",
    )
    fun SYS_086_findDistinctLinkedProjectIds_scopes_to_currently_linked_projects_only() {
        val linkedComponent = componentRepository.save(ComponentEntity(componentKey = "LINKED-SCOPE-COMPONENT"))
        val linkedProject = teamcityProjectRepository.save(TeamcityProjectEntity(projectId = "LINKED-SCOPE-PROJECT"))
        val orphanProject = teamcityProjectRepository.save(TeamcityProjectEntity(projectId = "ORPHAN-SCOPE-PROJECT"))

        // Only the linked project gets a version_line row; the orphan is stored in
        // teamcity_project (as sync's append-only history would leave it) with no current link.
        versionLineRepository.save(VersionLineEntity(component = linkedComponent, teamcityProject = linkedProject))

        val scope = versionLineRepository.findDistinctLinkedProjectIds().toSet()

        assertTrue(
            scope.contains(linkedProject.projectId),
            "a project referenced by a current version_line row must be in scope",
        )
        assertFalse(
            scope.contains(orphanProject.projectId),
            "a project with no current version_line row must be excluded from scope, even though " +
                "it exists in teamcity_project",
        )
    }

    @Test
    @DisplayName("SYS-086 a project reachable through two version lines is reported once")
    fun SYS_086_findDistinctLinkedProjectIds_deduplicates_multiple_version_lines_to_same_project() {
        val componentOne = componentRepository.save(ComponentEntity(componentKey = "DEDUP-SCOPE-COMPONENT-1"))
        val componentTwo = componentRepository.save(ComponentEntity(componentKey = "DEDUP-SCOPE-COMPONENT-2"))
        val sharedProject = teamcityProjectRepository.save(TeamcityProjectEntity(projectId = "DEDUP-SCOPE-SHARED-PROJECT"))

        versionLineRepository.save(VersionLineEntity(component = componentOne, teamcityProject = sharedProject))
        versionLineRepository.save(VersionLineEntity(component = componentTwo, teamcityProject = sharedProject))

        val scope = versionLineRepository.findDistinctLinkedProjectIds()

        assertEquals(
            1,
            scope.count { it == sharedProject.projectId },
            "a shared project must appear exactly once, not once per version line",
        )
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
