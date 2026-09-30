package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import java.util.UUID

/**
 * Provenance rule (owner review): a placement FIELD (checkoutDirectory, sourcePath, or Build
 * Working Directory -- tracked independently, not as a bundle) is manual only if its LAST audited
 * change is a real user write that is NOT tagged as a sync
 * (`PlacementEditHistory.SYNC_CHANGE_COMMENT_PREFIX`). A field with no audit record at all — a null
 * value, a V8 back-fill, a never-touched V9 BWD — is non-manual: nothing has ever "really" changed
 * it. A value last written by Sync itself is also non-manual, so a re-Diff after a Sync can resolve
 * again instead of getting stuck on its own write.
 */
class PlacementEditHistoryTest {
    private val auditLogRepository = mock<AuditLogRepository>()
    private val componentId: UUID = UUID.randomUUID()
    private val appVcsPath = "ssh://h/prj/app.git"

    private fun history() = PlacementEditHistory(auditLogRepository)

    private fun vcsSnapshot(
        cd: String?,
        sp: String?,
        bwd: String? = null,
        vcsPath: String = appVcsPath,
    ) = mapOf(
        "vcsEntries" to listOf(mapOf("vcsPath" to vcsPath, "checkoutDirectory" to cd, "sourcePath" to sp)),
        "buildWorkingDirectory" to bwd,
    )

    private fun row(
        old: Map<String, Any?>?,
        new: Map<String, Any?>?,
        changeComment: String? = null,
        action: String = "UPDATE",
    ) = AuditLogEntity(
        entityType = "Component",
        entityId = componentId.toString(),
        action = action,
        oldValue = old,
        newValue = new,
        changeComment = changeComment,
    )

    /** Rows in NEWEST-FIRST order, as the repository query returns them. */
    private fun stub(vararg rows: AuditLogEntity) {
        whenever(
            auditLogRepository.findByEntityTypeAndEntityIdAndActionInOrderByChangedAtDesc(
                "Component",
                componentId.toString(),
                listOf("UPDATE", "RENAME"),
            ),
        ).thenReturn(rows.toList())
    }

    @Test
    fun `no audit row at all is non-manual (a first import over a null value)`() {
        stub()
        assertFalse(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
        assertFalse(history().isSourcePathManuallySet(componentId, appVcsPath))
        assertFalse(history().isBuildWorkingDirectoryManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `an audit row present but never changing this repository is non-manual (V8's silent backfill)`() {
        // V8's raw-SQL backfill left no audit row of its own; an unrelated real edit still
        // snapshots the current state into both oldValue and newValue identically for this repo,
        // since neither side of that edit touched it.
        stub(row(vcsSnapshot(cd = "app", sp = null, bwd = "app"), vcsSnapshot(cd = "app", sp = null, bwd = "app")))
        assertFalse(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
        assertFalse(history().isSourcePathManuallySet(componentId, appVcsPath))
        assertFalse(history().isBuildWorkingDirectoryManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `the last change being a real untagged user write is manual`() {
        stub(row(vcsSnapshot(null, null), vcsSnapshot("app", null)))
        assertTrue(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `the last change being sync-tagged is not manual even though the value changed (resolved after a sync)`() {
        stub(row(vcsSnapshot(null, null), vcsSnapshot("app", null), changeComment = "sync from TeamCity (job=diff-1)"))
        assertFalse(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `TeamCity changing after a sync still resolves on re-diff (only the LAST change is looked at)`() {
        // Newest first: the user's edit is now further back than the sync that followed it, so the
        // sync (tagged) is what decides.
        val userEdit = row(vcsSnapshot(null, null), vcsSnapshot("app", null))
        val sync = row(vcsSnapshot("app", null), vcsSnapshot("app2", null), changeComment = "sync from TeamCity (job=diff-2)")
        stub(sync, userEdit)
        assertFalse(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `a user edit after a sync is manual again`() {
        val sync = row(vcsSnapshot("app", null), vcsSnapshot("app2", null), changeComment = "sync from TeamCity (job=diff-2)")
        val userEdit = row(vcsSnapshot("app2", null), vcsSnapshot("custom", null))
        stub(userEdit, sync)
        assertTrue(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `a snapshot for a different repository does not flag this one`() {
        stub(
            row(
                vcsSnapshot(null, null, vcsPath = "ssh://h/prj/other.git"),
                vcsSnapshot("other", null, vcsPath = "ssh://h/prj/other.git"),
            ),
        )
        assertFalse(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `a vcs-settings override-row snapshot is found under fieldOverride markerChildren`() {
        fun overrideSnapshot(
            cd: String?,
            bwd: String?,
        ) = mapOf(
            "fieldOverride[vcs.settings]" to mapOf(
                "markerChildren" to mapOf(
                    "vcsEntries" to listOf(mapOf("vcsPath" to appVcsPath, "checkoutDirectory" to cd, "sourcePath" to null)),
                    "buildWorkingDirectory" to bwd,
                ),
            ),
        )
        stub(row(overrideSnapshot(null, null), overrideSnapshot("core", "core/app")))
        assertTrue(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
        assertTrue(history().isBuildWorkingDirectoryManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `build working directory provenance is scoped to the section mentioning this vcsPath`() {
        // A component with two vcs.settings rows: this audit row is for the OTHER row (a different
        // vcsPath); its BWD change must not flag the row we're asking about.
        fun otherRowSnapshot(bwd: String?) =
            mapOf(
                "fieldOverride[vcs.settings]" to mapOf(
                    "markerChildren" to mapOf(
                        "vcsEntries" to listOf(
                            mapOf("vcsPath" to "ssh://h/prj/other.git", "checkoutDirectory" to null, "sourcePath" to null),
                        ),
                        "buildWorkingDirectory" to bwd,
                    ),
                ),
            )
        stub(row(otherRowSnapshot(null), otherRowSnapshot("changed-for-other-row")))
        assertFalse(history().isBuildWorkingDirectoryManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `checkoutDirectory and sourcePath are tracked independently (owner review finding 2 hardening, RED)`() {
        // Codex second-pass finding: the old (checkoutDirectory, sourcePath) PAIR-based comparison
        // let a Sync write that touches ONLY sourcePath "launder" an earlier MANUAL checkoutDirectory
        // edit -- since the pair as a whole differs on the sync row, the pair-based check found its
        // sync tag and called the WHOLE entry non-manual, silently permitting Sync to later overwrite
        // the user's checkoutDirectory too. Each field must be judged by its OWN last-changing row.
        val userEditsCdOnly = row(vcsSnapshot(cd = null, sp = null), vcsSnapshot(cd = "custom", sp = null))
        val syncEditsSpOnly = row(
            vcsSnapshot(cd = "custom", sp = null),
            vcsSnapshot(cd = "custom", sp = "core"),
            changeComment = "sync from TeamCity (job=1)",
        )
        stub(syncEditsSpOnly, userEditsCdOnly) // newest first

        assertTrue(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
        assertFalse(history().isSourcePathManuallySet(componentId, appVcsPath))
    }

    @Test
    fun `a placement set when the component was created is manual (review P1-2, RED)`() {
        val create = row(old = null, new = vcsSnapshot(cd = "app", sp = "svc", bwd = "app/svc"), action = "CREATE")
        whenever(
            auditLogRepository.findByEntityTypeAndEntityIdAndActionInOrderByChangedAtDesc(
                "Component",
                componentId.toString(),
                listOf("CREATE", "UPDATE", "RENAME"),
            ),
        ).thenReturn(listOf(create))

        assertTrue(history().isCheckoutDirectoryManuallySet(componentId, appVcsPath))
        assertTrue(history().isSourcePathManuallySet(componentId, appVcsPath))
        assertTrue(history().isBuildWorkingDirectoryManuallySet(componentId, appVcsPath))
    }
}
