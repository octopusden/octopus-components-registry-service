package org.octopusden.octopus.components.registry.server.service.impl

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.ComponentImage
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponentVersion

/**
 * Post-#192 review fixups (Group 1): stale-git-fallback guards + double-count
 * elimination on `ComponentRoutingResolver` methods that are NOT covered by the
 * MIG-049 union-merge fix in [ComponentRoutingResolverProjectNotFoundTest].
 *
 * The shared bug shape: db-first method's `catch (e: Exception)` falls back to
 * gitResolver. For a component routed to `db` in [ComponentSourceRegistry],
 * gitResolver may still return a stale legacy DSL match. Returning that match
 * causes silent data corruption visible to v1/v2 callers as "200 with stale
 * payload" instead of "404 / empty".
 *
 * Items covered here: 1.1, 1.4, 1.4b, 1.5, 1.6 (see
 * ~/.claude/plans/pr-192-review-fixup-plan.md). Items 1.2/1.3 are demoted to
 * Group 6-H since their return types do not expose `componentName` for a
 * stale-guard check.
 *
 * Pure in-memory tests: mocked resolvers, no Spring context, no global
 * fixtures (per `feedback_regression_guards_avoid_global_fixtures`).
 */
@Timeout(10, unit = TimeUnit.SECONDS)
class ComponentRoutingResolverStaleAndNotFoundTest {

    private lateinit var gitResolver: ComponentRegistryResolverImpl
    private lateinit var dbResolver: DatabaseComponentRegistryResolver
    private lateinit var sourceRegistry: ComponentSourceRegistry
    private lateinit var routing: ComponentRoutingResolver

    @BeforeEach
    fun setUp() {
        gitResolver = mock(ComponentRegistryResolverImpl::class.java)
        dbResolver = mock(DatabaseComponentRegistryResolver::class.java)
        sourceRegistry = mock(ComponentSourceRegistry::class.java)
        doReturn(emptySet<String>()).`when`(sourceRegistry).getDbComponentNames()
        routing = ComponentRoutingResolver(gitResolver, dbResolver, sourceRegistry)
    }

    // =========================================================================
    // 1.1 getJiraComponentByProjectAndVersion stale-guard + NotFound
    // =========================================================================

    private fun mockJiraComponentVersion(componentName: String): JiraComponentVersion {
        val jcv = mock(JiraComponentVersion::class.java)
        val cv = mock(ComponentVersion::class.java)
        doReturn(componentName).`when`(cv).componentName
        doReturn(cv).`when`(jcv).componentVersion
        return jcv
    }

    @Test
    @DisplayName(
        "1.1: getJiraComponentByProjectAndVersion — db NotFound + git returns DB-sourced match " +
            "→ throws NotFound (stale-git guard) instead of returning stale gitResult",
    )
    fun pr192_1_1_jiraComponentByProjectAndVersion_dbNotFound_gitStaleDbSourced_throwsNotFound() {
        val projectKey = "alpha-proj"
        val version = "1.0.0"
        val migratedComponent = "migrated-widget"
        doReturn(setOf(migratedComponent)).`when`(sourceRegistry).getDbComponentNames()
        doThrow(NotFoundException("not in db"))
            .`when`(dbResolver).getJiraComponentByProjectAndVersion(projectKey, version)
        doReturn(mockJiraComponentVersion(migratedComponent))
            .`when`(gitResolver).getJiraComponentByProjectAndVersion(projectKey, version)

        assertThrows(NotFoundException::class.java) {
            routing.getJiraComponentByProjectAndVersion(projectKey, version)
        }
    }

    @Test
    @DisplayName(
        "1.1 anti-regression: db NotFound + git returns NON-db-sourced match " +
            "→ returns gitResult (legitimate git-only project)",
    )
    fun pr192_1_1_jiraComponentByProjectAndVersion_dbNotFound_gitFreshNonDbSourced_returnsGit() {
        val projectKey = "beta-proj"
        val version = "1.0.0"
        val gitOnlyComponent = "legacy-widget"
        // sourceRegistry.getDbComponentNames() returns empty by default in setUp()
        val gitResult = mockJiraComponentVersion(gitOnlyComponent)
        doThrow(NotFoundException("not in db"))
            .`when`(dbResolver).getJiraComponentByProjectAndVersion(projectKey, version)
        doReturn(gitResult)
            .`when`(gitResolver).getJiraComponentByProjectAndVersion(projectKey, version)

        val result = routing.getJiraComponentByProjectAndVersion(projectKey, version)
        assertEquals(gitResult, result)
    }

    @Test
    @DisplayName(
        "1.1 anti-regression: db succeeds → returns dbResult, gitResolver not called",
    )
    fun pr192_1_1_jiraComponentByProjectAndVersion_dbSucceeds_returnsDb() {
        val projectKey = "gamma-proj"
        val version = "2.0.0"
        val dbResult = mockJiraComponentVersion("db-widget")
        doReturn(dbResult)
            .`when`(dbResolver).getJiraComponentByProjectAndVersion(projectKey, version)

        val result = routing.getJiraComponentByProjectAndVersion(projectKey, version)
        assertEquals(dbResult, result)
        verify(gitResolver, never()).getJiraComponentByProjectAndVersion(projectKey, version)
    }

    @Test
    @DisplayName(
        "1.1 anti-regression: db NotFound + git also NotFound → NotFoundException propagates",
    )
    fun pr192_1_1_jiraComponentByProjectAndVersion_bothNotFound_throws() {
        val projectKey = "delta-proj"
        val version = "1.0.0"
        doThrow(NotFoundException("not in db"))
            .`when`(dbResolver).getJiraComponentByProjectAndVersion(projectKey, version)
        doThrow(NotFoundException("not in git"))
            .`when`(gitResolver).getJiraComponentByProjectAndVersion(projectKey, version)

        assertThrows(NotFoundException::class.java) {
            routing.getJiraComponentByProjectAndVersion(projectKey, version)
        }
    }

    // =========================================================================
    // 1.4 findComponentByArtifact (single) stale-guard
    // =========================================================================

    private fun mockVersionedComponent(id: String): VersionedComponent {
        val vc = mock(VersionedComponent::class.java)
        doReturn(id).`when`(vc).id
        return vc
    }

    @Test
    @DisplayName(
        "1.4: findComponentByArtifact — db NotFound + git returns DB-sourced match " +
            "→ throws NotFound (stale-git guard)",
    )
    fun pr192_1_4_findComponentByArtifact_dbNotFound_gitStaleDbSourced_throwsNotFound() {
        val artifact = ArtifactDependency("org.test", "widget", "1.0.0")
        val migratedComponent = "migrated-widget"
        doReturn(setOf(migratedComponent)).`when`(sourceRegistry).getDbComponentNames()
        doThrow(NotFoundException("not in db"))
            .`when`(dbResolver).findComponentByArtifact(artifact)
        doReturn(mockVersionedComponent(migratedComponent))
            .`when`(gitResolver).findComponentByArtifact(artifact)

        assertThrows(NotFoundException::class.java) {
            routing.findComponentByArtifact(artifact)
        }
    }

    @Test
    @DisplayName(
        "1.4 anti-regression: db NotFound + git returns NON-db-sourced match → returns gitResult",
    )
    fun pr192_1_4_findComponentByArtifact_dbNotFound_gitFreshNonDbSourced_returnsGit() {
        val artifact = ArtifactDependency("org.test", "widget", "1.0.0")
        val gitOnlyComponent = "legacy-widget"
        val gitResult = mockVersionedComponent(gitOnlyComponent)
        doThrow(NotFoundException("not in db"))
            .`when`(dbResolver).findComponentByArtifact(artifact)
        doReturn(gitResult).`when`(gitResolver).findComponentByArtifact(artifact)

        val result = routing.findComponentByArtifact(artifact)
        assertEquals(gitResult, result)
    }

    // =========================================================================
    // 1.4b findComponentsByArtifact (batch) — db-authoritative, stale-git guard
    // =========================================================================

    @Test
    @DisplayName(
        "1.4b: findComponentsByArtifact — both resolvers return same id, component is DB-sourced " +
            "→ returns dbResult (authoritative), NOT null",
    )
    fun pr192_1_4b_findComponentsByArtifact_bothMatch_dbSourced_returnsDb() {
        val artifact = ArtifactDependency("org.test", "widget", "1.0.0")
        val artifacts = setOf(artifact)
        val migratedComponent = "migrated-widget"
        val dbResult = mockVersionedComponent(migratedComponent)
        val gitStaleResult = mockVersionedComponent(migratedComponent)
        doReturn(setOf(migratedComponent)).`when`(sourceRegistry).getDbComponentNames()
        doReturn(mapOf(artifact to gitStaleResult))
            .`when`(gitResolver).findComponentsByArtifact(artifacts)
        doReturn(mapOf(artifact to dbResult))
            .`when`(dbResolver).findComponentsByArtifact(artifacts)

        val result = routing.findComponentsByArtifact(artifacts)
        assertEquals(dbResult, result[artifact])
    }

    @Test
    @DisplayName(
        "1.4b: findComponentsByArtifact — db has no match, git stale match for DB-sourced component " +
            "→ returns null (stale guard), NOT gitResult",
    )
    fun pr192_1_4b_findComponentsByArtifact_dbMiss_gitStaleDbSourced_returnsNull() {
        val artifact = ArtifactDependency("org.test", "widget", "1.0.0")
        val artifacts = setOf(artifact)
        val migratedComponent = "migrated-widget"
        val gitStaleResult = mockVersionedComponent(migratedComponent)
        doReturn(setOf(migratedComponent)).`when`(sourceRegistry).getDbComponentNames()
        doReturn(mapOf(artifact to gitStaleResult))
            .`when`(gitResolver).findComponentsByArtifact(artifacts)
        doReturn(emptyMap<ArtifactDependency, VersionedComponent>())
            .`when`(dbResolver).findComponentsByArtifact(artifacts)

        val result = routing.findComponentsByArtifact(artifacts)
        assertNull(result[artifact])
    }

    @Test
    @DisplayName(
        "1.4b P1-A guard (Opus): dbResult present for a component NOT in dbNames " +
            "(partial-migration: component_source row missing) + git stale matches a DIFFERENT " +
            "db-sourced component → routing must return dbResult, not null (no false-drop)",
    )
    fun pr192_1_4b_findComponentsByArtifact_dbHit_componentNotInDbNames_gitStaleOtherDb_returnsDb() {
        val artifact = ArtifactDependency("org.test", "widget", "1.0.0")
        val artifacts = setOf(artifact)
        // partially-migrated: dbResult component is in `component` table but not in
        // `component_source` table yet (failed migration / race)
        val dbComponent = "partial-widget"
        // a DIFFERENT db-sourced component that git stale-matches the same artifact
        val otherDbComponent = "other-db-widget"
        val dbResult = mockVersionedComponent(dbComponent)
        val gitStaleOther = mockVersionedComponent(otherDbComponent)
        doReturn(setOf(otherDbComponent)).`when`(sourceRegistry).getDbComponentNames()
        doReturn(mapOf(artifact to gitStaleOther))
            .`when`(gitResolver).findComponentsByArtifact(artifacts)
        doReturn(mapOf(artifact to dbResult))
            .`when`(dbResolver).findComponentsByArtifact(artifacts)

        val result = routing.findComponentsByArtifact(artifacts)
        assertEquals(dbResult, result[artifact], "dbResult must win even when its id is not in dbNames")
    }

    @Test
    @DisplayName(
        "1.4b anti-regression: git-sourced component only matched in git → returns gitResult",
    )
    fun pr192_1_4b_findComponentsByArtifact_gitOnly_returnsGit() {
        val artifact = ArtifactDependency("org.test", "legacy", "1.0.0")
        val artifacts = setOf(artifact)
        val gitOnlyComponent = "legacy-widget"
        val gitResult = mockVersionedComponent(gitOnlyComponent)
        doReturn(mapOf(artifact to gitResult))
            .`when`(gitResolver).findComponentsByArtifact(artifacts)
        doReturn(emptyMap<ArtifactDependency, VersionedComponent>())
            .`when`(dbResolver).findComponentsByArtifact(artifacts)

        val result = routing.findComponentsByArtifact(artifacts)
        assertEquals(gitResult, result[artifact])
    }

    // =========================================================================
    // 1.5 getComponentsCountByBuildSystem — DEMOTED to Group 6-I follow-up.
    // Fix requires refactoring private EscrowModule.getBuildSystem() /
    // isArchived() extensions (currently class-private in
    // ComponentRegistryResolverImpl and DatabaseComponentRegistryResolver) to
    // a shared util. That refactor expands blast radius beyond planned Group
    // 1 scope. Double-count affects monitoring metrics only, not user-facing
    // API contract.
    // =========================================================================

    // =========================================================================
    // 1.6 findComponentsByDockerImages — source precedence
    // =========================================================================

    @Test
    @DisplayName(
        "1.6: findComponentsByDockerImages — component DB-sourced, DB no image match, " +
            "git stale match → empty (NOT git fallback)",
    )
    fun pr192_1_6_findComponentsByDockerImages_dbSourced_gitStale_returnsEmpty() {
        val image = Image("registry.test/migrated-widget", "1.0.0")
        val images = setOf(image)
        val migratedComponent = "migrated-widget"
        val gitStaleMatch = ComponentImage(component = migratedComponent, version = "1.0.0", image = image)
        doReturn(setOf(migratedComponent)).`when`(sourceRegistry).getDbComponentNames()
        doReturn(setOf(gitStaleMatch)).`when`(gitResolver).findComponentsByDockerImages(images)
        doReturn(emptySet<ComponentImage>()).`when`(dbResolver).findComponentsByDockerImages(images)

        val result = routing.findComponentsByDockerImages(images)
        // DB-sourced component with no DB match → stale git result must be filtered out.
        assertEquals(emptySet<ComponentImage>(), result)
    }

    @Test
    @DisplayName(
        "1.6 anti-regression: git-only component matched in git → returned",
    )
    fun pr192_1_6_findComponentsByDockerImages_gitOnly_returnsGit() {
        val image = Image("registry.test/legacy-widget", "1.0.0")
        val images = setOf(image)
        val gitOnlyComponent = "legacy-widget"
        val gitMatch = ComponentImage(component = gitOnlyComponent, version = "1.0.0", image = image)
        doReturn(setOf(gitMatch)).`when`(gitResolver).findComponentsByDockerImages(images)
        doReturn(emptySet<ComponentImage>()).`when`(dbResolver).findComponentsByDockerImages(images)

        val result = routing.findComponentsByDockerImages(images)
        assertEquals(setOf(gitMatch), result)
    }
}
