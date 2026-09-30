package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.teamcity.validation.EnrichedTcProjectFetcher
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildTypes
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRootEntries
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRootEntry
import java.util.UUID
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject as ExternalTeamcityProject

private fun prop(
    name: String,
    value: String?,
) = TeamcityProperty(name = name, value = value, inherited = null, type = null)

private fun props(vararg pairs: Pair<String, String?>) = TeamcityProperties(pairs.map { (n, v) -> prop(n, v) })

private fun vcsRoot(url: String) =
    TeamcityVcsRoot(
        id = url,
        name = url,
        vcsName = "jetbrains.git",
        href = "",
        project = null,
        projectLocator = null,
        properties = props("url" to url),
    )

private fun entry(
    url: String,
    rules: String?,
) = TeamcityVcsRootEntry(id = url, vcsRoot = vcsRoot(url), checkoutRules = rules ?: "")

private fun bareBuildType(
    id: String,
    paused: Boolean = false,
    templates: TeamcityBuildTypes? = null,
    template: TeamcityBuildType? = null,
    parameters: TeamcityProperties? = null,
    vcsRoots: TeamcityVcsRootEntries? = null,
) = TeamcityBuildType(
    id = id,
    name = id,
    projectId = "P",
    projectName = "P",
    href = "",
    vcsRoots = vcsRoots,
    parameters = parameters,
    webUrl = "",
    templateFlag = false,
    project = null,
    templates = templates,
    template = template,
    settings = null,
    steps = null,
    features = null,
    triggers = null,
    snapshotDependencies = null,
    paused = paused,
)

private fun compileBuildType(
    id: String,
    paused: Boolean = false,
    workDir: String? = null,
    roots: List<Pair<String, String?>> = emptyList(),
) = bareBuildType(
    id = id,
    paused = paused,
    templates = TeamcityBuildTypes(listOf(bareBuildType(id = "CDGradleBuild"))),
    parameters = workDir?.let { props("WORK_DIR" to it) },
    vcsRoots = TeamcityVcsRootEntries(roots.map { (url, rule) -> entry(url, rule) }),
)

private fun project(vararg buildTypes: TeamcityBuildType): ExternalTeamcityProject =
    TeamcityProject(
        id = "P",
        name = "P",
        parentProjectId = null,
        archived = false,
        href = "",
        webUrl = "",
        parentProject = null,
        buildTypes = TeamcityBuildTypes(buildTypes.toList()),
        templates = null,
        parameters = null,
        projects = null,
    )

/** Fixed responses/exceptions per project id; no caching/TTL behaviour needed for these tests. */
private class FakeEnrichedTcProjectFetcher(
    private val projects: Map<String, ExternalTeamcityProject?> = emptyMap(),
    private val errors: Map<String, Exception> = emptyMap(),
) : EnrichedTcProjectFetcher {
    override fun fetch(projectId: String): ExternalTeamcityProject? {
        errors[projectId]?.let { throw it }
        return projects[projectId]
    }

    override fun invalidateAll() = Unit
}

class TeamcityPlacementDiffServiceTest {
    private val appId = "ssh://h/prj/app-one.git"
    private val gatewayId = "ssh://h/prj/app-two.git"

    private fun component(
        key: String = "comp-one",
        archived: Boolean = false,
    ): ComponentEntity = ComponentEntity(id = UUID.randomUUID(), componentKey = key, archived = archived)

    private fun multiRootRow(
        component: ComponentEntity,
        currentGatewayCd: String? = null,
        currentAppCd: String? = null,
        currentBwd: String? = null,
    ): ComponentConfigurationEntity {
        val row = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            rowType = "BASE",
            buildWorkingDirectory = currentBwd,
        )
        row.vcsEntries += VcsSettingsEntryEntity(
            componentConfiguration = row,
            name = "app-two",
            vcsPath = gatewayId,
            sortOrder = 0,
            checkoutDirectory = currentGatewayCd,
        )
        row.vcsEntries += VcsSettingsEntryEntity(
            componentConfiguration = row,
            name = "app-one",
            vcsPath = appId,
            sortOrder = 1,
            checkoutDirectory = currentAppCd,
        )
        return row
    }

    /** No audit history unless a test stubs one — real PlacementEditHistory, everything else faked/mocked. */
    private fun editHistory(vararg rows: AuditLogEntity): PlacementEditHistory {
        val auditLogRepository = mock<AuditLogRepository>()
        whenever(
            auditLogRepository.findByEntityTypeAndEntityIdAndActionInOrderByChangedAtDesc(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            ),
        ).thenReturn(rows.toList())
        return PlacementEditHistory(auditLogRepository)
    }

    private fun service(
        rows: List<ComponentConfigurationEntity>,
        projectIdsByComponent: Map<UUID, List<String>>,
        fetcher: EnrichedTcProjectFetcher,
        editHistory: PlacementEditHistory = editHistory(),
    ): TeamcityPlacementDiffService {
        val configRepo = mock<ComponentConfigurationRepository>()
        whenever(configRepo.findAllRowsWithVcsEntries()).thenReturn(rows)
        val versionLineRepo = mock<VersionLineRepository>()
        projectIdsByComponent.forEach { (id, projects) ->
            whenever(versionLineRepo.findDistinctTeamcityProjectIdsByComponentId(id)).thenReturn(projects)
        }
        return TeamcityPlacementDiffService(configRepo, versionLineRepo, fetcher, editHistory)
    }

    @Test
    fun `a multi-root row resolves and is applyable`() {
        val comp = component()
        val row = multiRootRow(comp)
        val bt = compileBuildType("compileA", workDir = null, roots = listOf(gatewayId to "", appId to "+:. => app-one"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        val result = svc.runDiff()

        assertEquals(1, result.rows.size)
        val diff = result.rows.single()
        assertEquals(PlacementDiffRowStatus.RESOLVED, diff.status)
        assertEquals("app-one", diff.entries[1].derivedCheckoutDirectory)
        assertNull(diff.derivedBuildWorkingDirectory)
    }

    @Test
    fun `a row already matching TeamCity is in sync`() {
        val comp = component()
        val row = multiRootRow(comp, currentAppCd = "app-one")
        val bt = compileBuildType("compileA", roots = listOf(gatewayId to "", appId to "+:. => app-one"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        assertEquals(
            PlacementDiffRowStatus.IN_SYNC,
            svc
                .runDiff()
                .rows
                .single()
                .status,
        )
    }

    @Test
    fun `a manually re-saved value is reported as manual edit, not overwritten`() {
        val comp = component()
        val row = multiRootRow(comp, currentAppCd = "custom-name")
        val bt = compileBuildType("compileA", roots = listOf(gatewayId to "", appId to "+:. => app-one"))
        val manualHistory = editHistory(
            AuditLogEntity(
                entityType = "Component",
                entityId = comp.id.toString(),
                action = "UPDATE",
                newValue = mapOf(
                    "vcsEntries" to
                        listOf(mapOf("vcsPath" to appId, "checkoutDirectory" to "custom-name", "sourcePath" to null)),
                ),
            ),
        )
        val svc = service(
            listOf(row),
            mapOf(comp.id!! to listOf("P")),
            FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))),
            manualHistory,
        )

        assertEquals(
            PlacementDiffRowStatus.MANUAL_EDIT,
            svc
                .runDiff()
                .rows
                .single()
                .status,
        )
    }

    @Test
    fun `disagreeing compile configurations conflict`() {
        val comp = component()
        val row = multiRootRow(comp)
        val a = compileBuildType("a", roots = listOf(gatewayId to "", appId to "+:. => app-one"))
        val b = compileBuildType("b", roots = listOf(gatewayId to "+:. => gw", appId to "+:. => app-one"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(a, b))))

        assertEquals(
            PlacementDiffRowStatus.CONFLICT,
            svc
                .runDiff()
                .rows
                .single()
                .status,
        )
    }

    @Test
    fun `a 403 reading the project surfaces as a clear permission error`() {
        val comp = component()
        val row = multiRootRow(comp)
        val forbidden = RuntimeException("403 Forbidden: no access")
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(errors = mapOf("P" to forbidden)))

        val diff = svc.runDiff().rows.single()
        assertEquals(PlacementDiffRowStatus.TC_ERROR, diff.status)
        assertTrue(diff.notes.single().contains("no permission to read VCS root entries"))
    }

    @Test
    fun `a component with no TeamCity link is skipped entirely`() {
        val comp = component()
        val row = multiRootRow(comp)
        val svc = service(listOf(row), mapOf(comp.id!! to emptyList()), FakeEnrichedTcProjectFetcher())

        assertTrue(svc.runDiff().rows.isEmpty())
    }

    @Test
    fun `a single-root row with no checkout directory or build working directory anywhere is out of scope`() {
        val comp = component()
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = comp, rowType = "BASE")
        row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "main", vcsPath = appId, sortOrder = 0)
        val bt = compileBuildType("compileA", roots = listOf(appId to ""))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        assertTrue(svc.runDiff().rows.isEmpty())
    }

    @Test
    fun `a single-root row with a derived source path only, no checkout directory, is in scope`() {
        // ADR-001 allows a lone root with an empty Checkout Directory but a non-empty Source Path
        // (a monorepo subdirectory with no rename): `+:mapper` -> PlacementValue(null, "mapper").
        // Regression: the scope filter only looked at Checkout Directory / Build Working
        // Directory, silently dropping this row (no CD, no BWD needed since the root is taken).
        val comp = component()
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = comp, rowType = "BASE")
        row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "main", vcsPath = appId, sortOrder = 0)
        val bt = compileBuildType("compileA", roots = listOf(appId to "+:mapper"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        val diff = svc.runDiff().rows.single()
        assertEquals(PlacementDiffRowStatus.RESOLVED, diff.status)
        assertEquals("mapper", diff.entries.single().derivedSourcePath)
        assertNull(diff.entries.single().derivedCheckoutDirectory)
    }

    @Test
    fun `a single-root row with a derived checkout directory is in scope`() {
        // ADR-001: a lone root with a Checkout Directory leaves the checkout root empty, so a
        // Build Working Directory is required — the row derives fully only when WORK_DIR sets one.
        val comp = component()
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = comp, rowType = "BASE")
        row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "main", vcsPath = appId, sortOrder = 0)
        val bt = compileBuildType("compileA", workDir = "%teamcity.build.checkoutDir%/app", roots = listOf(appId to "+:. => app"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        val diff = svc.runDiff().rows.single()
        assertEquals(PlacementDiffRowStatus.RESOLVED, diff.status)
        assertEquals("app", diff.entries.single().derivedCheckoutDirectory)
        assertEquals("app", diff.derivedBuildWorkingDirectory)
    }

    @Test
    fun `a single-root row whose derived checkout directory has no build working directory is invalid (spec-conformance finding 3, RED)`() {
        // Same shape without WORK_DIR: still in scope (a derived CD exists), but ADR-001's rule --
        // every root has a Checkout Directory, so a Build Working Directory is required, and none
        // was derived -- is a CRS VALIDATION rule (VcsPlacementValidator.validateBuildWorkingDirectory),
        // not an unparseable rule shape, so this is INVALID, not UNEXPRESSIBLE.
        val comp = component()
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = comp, rowType = "BASE")
        row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "main", vcsPath = appId, sortOrder = 0)
        val bt = compileBuildType("compileA", roots = listOf(appId to "+:. => app"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        assertEquals(
            PlacementDiffRowStatus.INVALID,
            svc
                .runDiff()
                .rows
                .single()
                .status,
        )
    }

    @Test
    fun `two roots at the checkout root is invalid, not unexpressible (spec-conformance finding 3, RED)`() {
        // "At most one root at the checkout root" is a CRS validation rule
        // (VcsPlacementValidator.validateVcsPlacement's rootEntry check), not a rule-shape problem
        // -- both roots parse cleanly, they just conflict with each other under CRS's own rules.
        val comp = component()
        // A current value that differs from the (checkout-root) derivation, so the row isn't
        // trivially IN_SYNC before the INVALID check is even reached.
        val row = multiRootRow(comp, currentGatewayCd = "old-value")
        val bt = compileBuildType("compileA", roots = listOf(gatewayId to "", appId to ""))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        val diff = svc.runDiff().rows.single()
        assertEquals(PlacementDiffRowStatus.INVALID, diff.status)
        assertTrue(diff.notes.any { it.contains("checkout root") })
    }

    @Test
    fun `a derived source path outside the row is invalid (owner review finding 4, RED)`() {
        // `+:../outside` parses to a plain Source Path of "../outside" (parseCheckoutRule has no
        // opinion on its shape) -- but the SAME v4 write path a human PATCH uses rejects a
        // Source Path containing "..", so this must be reported INVALID, not RESOLVED.
        val comp = component()
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = comp, rowType = "BASE")
        row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "main", vcsPath = appId, sortOrder = 0)
        val bt = compileBuildType("compileA", roots = listOf(appId to "+:../outside"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        val diff = svc.runDiff().rows.single()
        assertEquals(PlacementDiffRowStatus.INVALID, diff.status)
        assertTrue(diff.notes.any { it.contains("sourcePath", ignoreCase = true) })
    }

    @Test
    fun `a kept name that WOULD collide falls back to main exactly as a real write would (owner review finding 4 hardening, RED)`() {
        // Codex second-pass finding: the first version of this check built its candidate names as
        // `derived checkoutDirectory ?: kept name` -- no exclusion, no fallback -- so it flagged
        // this row INVALID. But ComponentManagementServiceImpl.replaceVcsEntries (the REAL write)
        // excludes a kept name that collides with a NEW checkout directory and falls back to
        // "main" instead of failing -- so the real write would ACCEPT this row (GATEWAY ends up
        // named "main", not colliding with APP's "app-one"). Diff's candidate must derive names
        // the SAME way, or it reports INVALID for rows the real write happily accepts.
        val comp = component()
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = comp, rowType = "BASE")
        row.vcsEntries += VcsSettingsEntryEntity(
            componentConfiguration = row,
            name = "app-one",
            vcsPath = gatewayId,
            sortOrder = 0,
        )
        row.vcsEntries += VcsSettingsEntryEntity(
            componentConfiguration = row,
            name = "app-one-original",
            vcsPath = appId,
            sortOrder = 1,
        )
        val bt = compileBuildType("compileA", roots = listOf(gatewayId to "", appId to "+:. => app-one"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        val diff = svc.runDiff().rows.single()
        assertEquals(PlacementDiffRowStatus.RESOLVED, diff.status)
    }

    @Test
    fun `a collision that survives the real fallback (both land on the literal 'main') is still invalid`() {
        // The "main" fallback ITSELF can collide: if some entry's derived Checkout Directory is
        // literally "main", an unplaced root whose kept name collides (so it falls back to the
        // hardcoded default "main") lands on the SAME name as that other root -- a real v4 write
        // would reject this exactly the same way. Regression guard for the hardening above: the
        // fix must not overcorrect into treating every collision as fallback-safe.
        val comp = component()
        val row = ComponentConfigurationEntity(id = UUID.randomUUID(), component = comp, rowType = "BASE")
        row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "main", vcsPath = gatewayId, sortOrder = 0)
        row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "app-original", vcsPath = appId, sortOrder = 1)
        val bt = compileBuildType("compileA", roots = listOf(gatewayId to "", appId to "+:. => main"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        val diff = svc.runDiff().rows.single()
        assertEquals(PlacementDiffRowStatus.INVALID, diff.status)
        assertTrue(diff.notes.any { it.contains("main") })
    }

    @Test
    fun `a marker (vcs_settings) row is reported OUTSIDE_SCOPE, never derived (spec-conformance finding 1, RED)`() {
        val comp = component()
        val row = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = comp,
            rowType = "MARKER",
            overriddenAttribute = "vcs.settings",
        )
        row.vcsEntries += VcsSettingsEntryEntity(componentConfiguration = row, name = "main", vcsPath = appId, sortOrder = 0)
        val bt = compileBuildType("compileA", roots = listOf(appId to "+:. => app"))
        val svc = service(listOf(row), mapOf(comp.id!! to listOf("P")), FakeEnrichedTcProjectFetcher(mapOf("P" to project(bt))))

        val diff = svc.runDiff().rows.single()
        assertEquals(PlacementDiffRowStatus.OUTSIDE_SCOPE, diff.status)
        assertEquals("vcs.settings", diff.rowLabel)
        // Never derived: no TeamCity-chain-informed value is offered for a report-only row.
        assertNull(diff.entries.single().derivedCheckoutDirectory)
    }

    @Test
    fun `an archived component's row is reported OUTSIDE_SCOPE instead of omitted (spec-conformance finding 1, RED)`() {
        // No TeamCity project link is stubbed at all: an archived component's row must not need
        // one -- it is OUTSIDE_SCOPE unconditionally, without ever consulting the chain.
        val comp = component(archived = true)
        val row = multiRootRow(comp)
        val svc = service(listOf(row), emptyMap(), FakeEnrichedTcProjectFetcher())

        val diff = svc.runDiff().rows.single()
        assertEquals(PlacementDiffRowStatus.OUTSIDE_SCOPE, diff.status)
        assertTrue(diff.notes.any { it.contains("archived") })
    }
}
