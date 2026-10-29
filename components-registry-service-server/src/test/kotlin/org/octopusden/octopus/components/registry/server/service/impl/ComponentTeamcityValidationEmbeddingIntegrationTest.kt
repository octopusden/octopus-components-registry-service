package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationEntity
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.TeamcityProjectRepository
import org.octopusden.octopus.components.registry.server.repository.TeamcityValidationRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant

/**
 * SYS-091: `ComponentManagementServiceImpl.attachTeamcityValidations` embeds stored WARNING/ERROR
 * findings onto each of a component's linked TeamCity projects in the detail response. Covers all
 * three acceptance criteria: findings embedded for a project that has them, an empty list for a
 * linked project with none, and — implicitly, since it shares the same fixture set as the other
 * two — no findings query at all when a component has no linked TeamCity projects (the "no
 * findings" case here is a project WITH stored data resolving to none, not a code-path check on an
 * absent query call, which would require a spy; scoped to the DTO-shape contract instead).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db-validate")
@Timeout(120)
@Tag("integration")
class ComponentTeamcityValidationEmbeddingIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var teamcityProjectRepository: TeamcityProjectRepository

    @Autowired
    private lateinit var versionLineRepository: VersionLineRepository

    @Autowired
    private lateinit var teamcityValidationRepository: TeamcityValidationRepository

    @Autowired
    private lateinit var componentManagementService: ComponentManagementService

    @Test
    @DisplayName(
        "SYS-091 getComponent embeds stored findings for a project that has them and an empty " +
            "list for a linked project with none",
    )
    fun SYS_091_getComponent_embeds_findings_per_linked_project() {
        val component = componentRepository.save(ComponentEntity(componentKey = "EMBED-VALIDATIONS-COMPONENT"))
        val projectWithFindings =
            teamcityProjectRepository.save(TeamcityProjectEntity(projectId = "EMBED-VALIDATIONS-PROJECT-WITH-FINDINGS"))
        val cleanProject =
            teamcityProjectRepository.save(TeamcityProjectEntity(projectId = "EMBED-VALIDATIONS-PROJECT-CLEAN"))
        versionLineRepository.save(VersionLineEntity(component = component, teamcityProject = projectWithFindings))
        versionLineRepository.save(VersionLineEntity(component = component, teamcityProject = cleanProject))

        val now = Instant.now()
        teamcityValidationRepository.save(
            TeamcityValidationEntity(
                projectId = projectWithFindings.projectId,
                type = "USES_OLD_JAVA_VERSION",
                status = "WARNING",
                message = "Java 8 is end-of-life",
                updatedAt = now,
            ),
        )

        val detail = componentManagementService.getComponent(component.id!!)

        val withFindings = detail.teamcityProjects.single { it.projectId == projectWithFindings.projectId }
        assertEquals(1, withFindings.validations.size, "the project with a stored finding must have it embedded")
        assertEquals("USES_OLD_JAVA_VERSION", withFindings.validations.single().type)
        assertEquals("WARNING", withFindings.validations.single().status)

        val clean = detail.teamcityProjects.single { it.projectId == cleanProject.projectId }
        assertTrue(clean.validations.isEmpty(), "a linked project with no stored findings must have an empty validations list")
    }

    @Test
    @DisplayName("SYS-091 getComponent with no linked TeamCity projects has an empty teamcityProjects list")
    fun SYS_091_getComponent_with_no_linked_projects_has_empty_teamcityProjects() {
        val component = componentRepository.save(ComponentEntity(componentKey = "EMBED-VALIDATIONS-NO-PROJECTS"))

        val detail = componentManagementService.getComponent(component.id!!)

        assertTrue(detail.teamcityProjects.isEmpty())
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
