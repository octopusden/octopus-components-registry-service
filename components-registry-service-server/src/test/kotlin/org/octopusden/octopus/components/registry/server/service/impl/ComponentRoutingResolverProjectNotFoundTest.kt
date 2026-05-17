package org.octopusden.octopus.components.registry.server.service.impl

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry

/**
 * MIG-049: V2 project endpoints return 200 instead of 404 for unknown projects.
 *
 * `ComponentRoutingResolver`'s three union-merge methods (jira-components,
 * jira-component-version-ranges, components-distribution) wrap each resolver
 * call in `try { … } catch (e: Exception) { empty }`. When BOTH gitResolver
 * (V1) and dbResolver (V2) raise `NotFoundException` because the project key
 * is genuinely unknown, the routing layer silently substitutes an empty
 * collection. The controller returns 200 + `[]`/`{}` instead of the 404
 * `ControllerExceptionHandler` maps from `NotFoundException`.
 *
 * Trace-replay against the 2026-05-17 deploy of `feat/schema-v2-sql` surfaces
 * this as 27 STATUS_CODE_DIFF on `/jira-components` + 4 on
 * `/component-distributions` records.
 *
 * Pure in-memory tests: mocked resolvers, no Spring context, no global
 * fixtures (per `feedback_regression_guards_avoid_global_fixtures`).
 * Synthetic project keys only (`feedback_redacted_identifiers`).
 */
@Timeout(10, unit = TimeUnit.SECONDS)
class ComponentRoutingResolverProjectNotFoundTest {

    private lateinit var gitResolver: ComponentRegistryResolverImpl
    private lateinit var dbResolver: DatabaseComponentRegistryResolver
    private lateinit var sourceRegistry: ComponentSourceRegistry
    private lateinit var routing: ComponentRoutingResolver

    @BeforeEach
    fun setUp() {
        gitResolver = mock(ComponentRegistryResolverImpl::class.java)
        dbResolver = mock(DatabaseComponentRegistryResolver::class.java)
        sourceRegistry = mock(ComponentSourceRegistry::class.java)
        // Default: no DB-routed components — exercised methods read this from
        // sourceRegistry to deduplicate union-merge results.
        doReturn(emptySet<String>()).`when`(sourceRegistry).getDbComponentNames()
        routing = ComponentRoutingResolver(gitResolver, dbResolver, sourceRegistry)
    }

    // =========================================================================
    // MIG-049-001: getJiraComponentsByProject
    // =========================================================================

    @Test
    @DisplayName(
        "MIG-049-001: getJiraComponentsByProject re-throws NotFoundException " +
            "when both resolvers raise it (project genuinely unknown)",
    )
    fun mig049_001_jiraComponentsByProject_bothNotFound_reThrows() {
        val projectKey = "alpha-project"
        doThrow(NotFoundException("Project '$projectKey' is not found"))
            .`when`(gitResolver).getJiraComponentsByProject(projectKey)
        doThrow(NotFoundException("Project '$projectKey' is not found"))
            .`when`(dbResolver).getJiraComponentsByProject(projectKey)

        val ex =
            assertThrows(NotFoundException::class.java) {
                routing.getJiraComponentsByProject(projectKey)
            }
        assertEquals(
            "Project '$projectKey' is not found",
            ex.message,
            "Re-thrown NotFoundException must carry the project-key context",
        )
    }

    @Test
    @DisplayName(
        "MIG-049-001 anti-regression: gitResolver throws, dbResolver returns rows → routing returns the V2 rows (no throw)",
    )
    fun mig049_001_jiraComponentsByProject_gitNotFound_dbHasRows_returnsDb() {
        val projectKey = "beta-project"
        doThrow(NotFoundException("not in git")).`when`(gitResolver).getJiraComponentsByProject(projectKey)
        doReturn(setOf("widget-core", "widget-cli")).`when`(dbResolver).getJiraComponentsByProject(projectKey)

        val result = routing.getJiraComponentsByProject(projectKey)
        assertEquals(setOf("widget-core", "widget-cli"), result)
    }

    @Test
    @DisplayName(
        "MIG-049-001 anti-regression: gitResolver returns rows, dbResolver throws → routing returns the V1 rows (no throw)",
    )
    fun mig049_001_jiraComponentsByProject_gitHasRows_dbNotFound_returnsGit() {
        val projectKey = "gamma-project"
        doReturn(setOf("legacy-core")).`when`(gitResolver).getJiraComponentsByProject(projectKey)
        doThrow(NotFoundException("not in db")).`when`(dbResolver).getJiraComponentsByProject(projectKey)

        val result = routing.getJiraComponentsByProject(projectKey)
        assertEquals(setOf("legacy-core"), result)
    }

    @Test
    @DisplayName(
        "MIG-049-001 anti-regression: both resolvers return non-empty → routing returns the union (existing semantics preserved)",
    )
    fun mig049_001_jiraComponentsByProject_bothHaveRows_returnsUnion() {
        val projectKey = "delta-project"
        doReturn(setOf("git-only-a", "shared-b")).`when`(gitResolver).getJiraComponentsByProject(projectKey)
        doReturn(setOf("shared-b", "db-only-c")).`when`(dbResolver).getJiraComponentsByProject(projectKey)

        val result = routing.getJiraComponentsByProject(projectKey)
        assertEquals(setOf("git-only-a", "shared-b", "db-only-c"), result)
    }
}
