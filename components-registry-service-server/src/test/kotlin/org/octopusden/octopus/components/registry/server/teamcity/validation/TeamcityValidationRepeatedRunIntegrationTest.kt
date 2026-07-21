package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationEntity
import org.octopusden.octopus.components.registry.server.repository.TeamcityValidationRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant

/**
 * DB-level regression test for the repeated-run persistence bug flagged in PR #443 review
 * (pgorbachev, P1): a finding `type` that survives between two consecutive validation runs for
 * the same project must be deleted and re-inserted cleanly, not collide via a stale
 * managed/removed instance left in the persistence context by a derived (load-then-remove)
 * delete.
 *
 * Mirrors the "full per-project replace" sequence in `TeamcityValidationService.replaceFindings`:
 * delete-by-project-id, then save the new rows, each in its own transaction — run twice with an
 * overlapping `(projectId, type)` pair.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db-validate")
@Timeout(120)
@Tag("integration")
class TeamcityValidationRepeatedRunIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var teamcityValidationRepository: TeamcityValidationRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private fun replace(
        projectId: String,
        types: List<String>,
    ) {
        val now = Instant.now()
        transactionTemplate.executeWithoutResult {
            teamcityValidationRepository.deleteByProjectId(projectId)
            if (types.isNotEmpty()) {
                teamcityValidationRepository.saveAll(
                    types.map { type ->
                        TeamcityValidationEntity(
                            projectId = projectId,
                            type = type,
                            status = "WARNING",
                            message = "run for $type",
                            updatedAt = now,
                        )
                    },
                )
            }
        }
    }

    @Test
    @DisplayName(
        "two consecutive replacements with an overlapping (projectId, type) do not throw and leave only the latest row",
    )
    fun repeated_replace_with_surviving_type_does_not_collide() {
        val projectId = "REPEATED-RUN-PROJECT"

        // First run: two findings, including one that will survive into the next run.
        replace(projectId, listOf("USES_OLD_JAVA_VERSION", "OVERRIDES_DEFAULT_BUILD_STEP"))
        val afterFirst = teamcityValidationRepository.findByProjectIdIn(listOf(projectId))
        assertEquals(2, afterFirst.size, "expected 2 rows after the first run")

        // Second run: USES_OLD_JAVA_VERSION survives, OVERRIDES_DEFAULT_BUILD_STEP is gone, a new
        // type appears. This must not throw (e.g. ObjectDeletedException / PK-ordering failure).
        replace(projectId, listOf("USES_OLD_JAVA_VERSION", "MULTIPLE_JAVA_VERSIONS"))

        val afterSecond = teamcityValidationRepository.findByProjectIdIn(listOf(projectId))
        assertEquals(2, afterSecond.size, "expected exactly 2 rows after the second run (old set replaced)")
        assertEquals(
            setOf("USES_OLD_JAVA_VERSION", "MULTIPLE_JAVA_VERSIONS"),
            afterSecond.map { it.type }.toSet(),
        )
    }

    @Test
    @DisplayName("pruning with an empty known-project scope removes every stored row, including the last project")
    fun stale_row_pruning_with_empty_scope_removes_the_last_project() {
        val projectA = "FINAL-REMOVAL-PROJECT-A"
        val projectB = "FINAL-REMOVAL-PROJECT-B"
        replace(projectA, listOf("USES_OLD_JAVA_VERSION"))
        replace(projectB, listOf("OVERRIDES_DEFAULT_BUILD_STEP"))
        assertEquals(2, teamcityValidationRepository.findByProjectIdIn(listOf(projectA, projectB)).size)

        // Scope shrinks to empty (both projects unlinked) — mirrors
        // TeamcityValidationService.removeStaleProjects(knownProjectIds = emptySet()).
        val knownProjectIds = emptySet<String>()
        val stored = teamcityValidationRepository.findDistinctStoredProjectIds().toSet()
        val toRemove = stored.filter { it == projectA || it == projectB }.toSet() - knownProjectIds
        transactionTemplate.executeWithoutResult { teamcityValidationRepository.deleteByProjectIdIn(toRemove) }

        assertEquals(0, teamcityValidationRepository.findByProjectIdIn(listOf(projectA, projectB)).size)
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
