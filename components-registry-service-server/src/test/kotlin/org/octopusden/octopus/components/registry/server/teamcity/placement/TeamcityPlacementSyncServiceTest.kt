package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.BaseConfigurationRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpdateRequest
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import java.time.Instant
import java.util.UUID

/**
 * [TeamcityPlacementDiffService] is exercised fully by [TeamcityPlacementDiffServiceTest]; here it
 * is mocked (Mockito's default inline mock maker handles a final Kotlin class) so these tests focus
 * purely on the sync/skip decision and the write shape.
 */
class TeamcityPlacementSyncServiceTest {
    private val componentId = UUID.randomUUID()

    private fun row(
        status: PlacementDiffRowStatus,
        rowLabel: String = "BASE",
        rowId: UUID = UUID.randomUUID(),
        derivedCd: String? = "app",
        derivedBwd: String? = null,
    ) = PlacementRowDiff(
        componentId = componentId,
        componentKey = "comp-one",
        configurationRowId = rowId,
        versionRange = "(,0),[0,)",
        rowLabel = rowLabel,
        status = status,
        entries = listOf(
            PlacementEntryDiff(
                name = "main",
                vcsPath = "ssh://h/prj/app.git",
                branch = "master",
                tag = null,
                hotfixBranch = null,
                repositoryType = "GIT",
                currentCheckoutDirectory = null,
                currentSourcePath = null,
                derivedCheckoutDirectory = derivedCd,
                derivedSourcePath = null,
            ),
        ),
        currentBuildWorkingDirectory = null,
        derivedBuildWorkingDirectory = derivedBwd,
        sourceBuildTypeIds = listOf("compileA"),
        notes = emptyList(),
    )

    private fun service(
        diffService: TeamcityPlacementDiffService,
        componentManagementService: ComponentManagementService = mock(),
    ) = TeamcityPlacementSyncService(diffService, componentManagementService) to componentManagementService

    /** A [TeamcityPlacementDiffService] mock whose re-derivation (Sync always re-derives before
     * writing) returns exactly [rows] for this test's componentId. */
    private fun diffServiceReturning(rows: List<PlacementRowDiff>) =
        mock<TeamcityPlacementDiffService> {
            whenever(it.runDiff(setOf(componentId))).thenReturn(PlacementDiffResult(Instant.now(), rows))
        }

    @Test
    fun `a resolved row unchanged since the diff is applied through the base PATCH`() {
        val diffRow = row(PlacementDiffRowStatus.RESOLVED)
        val diffService = diffServiceReturning(listOf(diffRow))
        val (svc, cms) = service(diffService)
        val detail = mock<ComponentDetailResponse>()
        whenever(detail.version).thenReturn(5L)
        whenever(cms.getComponent(componentId)).thenReturn(detail)

        val result = svc.sync(setOf(componentId), PlacementDiffResult(Instant.now(), listOf(diffRow)), "alice")

        assertEquals(1, result.applied)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        val captor = argumentCaptor<ComponentUpdateRequest>()
        verify(cms).updateComponent(org.mockito.kotlin.eq(componentId), captor.capture())
        assertEquals(5L, captor.firstValue.version)
        assertEquals("sync from TeamCity", captor.firstValue.changeComment)
        assertEquals("app", (captor.firstValue.baseConfiguration as BaseConfigurationRequest).vcsEntries!!.single().checkoutDirectory)
        assertEquals("", captor.firstValue.baseConfiguration!!.buildWorkingDirectory) // root, cleared explicitly
    }

    @Test
    fun `a row whose fresh re-derivation differs from the diff snapshot is skipped`() {
        val snapshotRow = row(PlacementDiffRowStatus.RESOLVED, rowId = UUID.randomUUID())
        val freshRow = snapshotRow.copy(entries = snapshotRow.entries.map { it.copy(derivedCheckoutDirectory = "different") })
        val diffService = diffServiceReturning(listOf(freshRow))
        val (svc, cms) = service(diffService)

        val result = svc.sync(setOf(componentId), PlacementDiffResult(Instant.now(), listOf(snapshotRow)), "alice")

        assertEquals(0, result.applied)
        assertEquals(1, result.skipped)
        assertEquals(
            "skipped: changed since diff",
            result.components
                .single()
                .rows
                .single()
                .outcome,
        )
        verify(cms, org.mockito.kotlin.never()).updateComponent(any(), any())
    }

    @Test
    fun `a manual-edit row is skipped, never written`() {
        val diffRow = row(PlacementDiffRowStatus.MANUAL_EDIT)
        val diffService = diffServiceReturning(listOf(diffRow))
        val (svc, cms) = service(diffService)

        val result = svc.sync(setOf(componentId), PlacementDiffResult(Instant.now(), listOf(diffRow)), "alice")

        assertEquals(0, result.applied)
        assertEquals(
            "skipped: manual_edit",
            result.components
                .single()
                .rows
                .single()
                .outcome,
        )
        verify(cms, org.mockito.kotlin.never()).updateComponent(any(), any())
    }

    @Test
    fun `a vcs-settings override row is written through updateFieldOverride, not the base PATCH`() {
        val diffRow = row(PlacementDiffRowStatus.RESOLVED, rowLabel = "vcs.settings", derivedBwd = "app")
        val diffService = diffServiceReturning(listOf(diffRow))
        val (svc, cms) = service(diffService)

        val result = svc.sync(setOf(componentId), PlacementDiffResult(Instant.now(), listOf(diffRow)), "alice")

        assertEquals(1, result.applied)
        val captor = argumentCaptor<FieldOverrideUpdateRequest>()
        verify(cms).updateFieldOverride(
            org.mockito.kotlin.eq(componentId),
            org.mockito.kotlin.eq(diffRow.configurationRowId),
            captor.capture(),
        )
        assertEquals("app", captor.firstValue.markerChildren!!.buildWorkingDirectory)
        verify(cms, org.mockito.kotlin.never()).updateComponent(any(), any())
    }

    @Test
    fun `a failing write is counted and reported without aborting the rest`() {
        val diffRow = row(PlacementDiffRowStatus.RESOLVED)
        val diffService = diffServiceReturning(listOf(diffRow))
        val (svc, cms) = service(diffService)
        whenever(cms.getComponent(componentId)).thenThrow(RuntimeException("boom"))

        val result = svc.sync(setOf(componentId), PlacementDiffResult(Instant.now(), listOf(diffRow)), "alice")

        assertEquals(1, result.failed)
        assertEquals(
            "failed: boom",
            result.components
                .single()
                .rows
                .single()
                .outcome,
        )
    }

    @Test
    fun `a selected snapshot row that no longer appears in the fresh re-derivation is reported, not silently dropped`() {
        // Regression: iterating only the fresh rows would drop this row from the result entirely
        // (no outcome, no counter) if the component fell out of scope entirely on re-derivation —
        // e.g. its TeamCity link disappeared, or every entry stopped needing anything.
        val diffRow = row(PlacementDiffRowStatus.RESOLVED)
        val diffService = diffServiceReturning(emptyList())
        val (svc, cms) = service(diffService)

        val result = svc.sync(setOf(componentId), PlacementDiffResult(Instant.now(), listOf(diffRow)), "alice")

        assertEquals(0, result.applied)
        assertEquals(1, result.skipped)
        val outcome = result.components
            .single()
            .rows
            .single()
        assertEquals(diffRow.configurationRowId, outcome.configurationRowId)
        assertEquals("skipped: changed since diff", outcome.outcome)
        verify(cms, org.mockito.kotlin.never()).updateComponent(any(), any())
    }

    @Test
    fun `sync forces a live TeamCity read before re-deriving, never trusting the fetch cache`() {
        // Without this, a re-derivation inside the cache's TTL window could compare against the
        // exact same (now stale) response the original Diff read, defeating "changed since diff".
        val diffRow = row(PlacementDiffRowStatus.RESOLVED)
        val diffService = diffServiceReturning(listOf(diffRow))
        val (svc, cms) = service(diffService)
        val detail = mock<ComponentDetailResponse>()
        whenever(detail.version).thenReturn(1L)
        whenever(cms.getComponent(componentId)).thenReturn(detail)

        svc.sync(setOf(componentId), PlacementDiffResult(Instant.now(), listOf(diffRow)), "alice")

        verify(diffService).invalidateTeamcityCache()
    }
}
