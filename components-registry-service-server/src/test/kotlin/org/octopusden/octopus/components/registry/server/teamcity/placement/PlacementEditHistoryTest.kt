package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import java.time.Instant
import java.util.UUID

class PlacementEditHistoryTest {
    private val auditLogRepository = mock<AuditLogRepository>()
    private val componentId: UUID = UUID.randomUUID()
    private val v8AppliedAt = Instant.parse("2026-06-01T00:00:00Z")

    private fun history(v8At: Instant? = v8AppliedAt) = PlacementEditHistory(auditLogRepository) { v8At }

    /** [rows] are (oldValue, newValue) pairs — the real audit shape: both captured by the same
     * snapshot function, one before the patch and one after. */
    private fun stubRows(vararg rows: Pair<Map<String, Any?>, Map<String, Any?>>) {
        whenever(
            auditLogRepository.findByEntityTypeAndEntityIdAndChangedAtAfterAndActionIn(
                "Component",
                componentId.toString(),
                v8AppliedAt,
                listOf("UPDATE", "RENAME"),
            ),
        ).thenReturn(
            rows.map { (old, new) ->
                AuditLogEntity(
                    entityType = "Component",
                    entityId = componentId.toString(),
                    action = "UPDATE",
                    oldValue = old,
                    newValue = new,
                )
            },
        )
    }

    private fun vcsEntriesSnapshot(
        vcsPath: String,
        checkoutDirectory: String?,
        sourcePath: String?,
    ) = mapOf("vcsEntries" to listOf(mapOf("vcsPath" to vcsPath, "checkoutDirectory" to checkoutDirectory, "sourcePath" to sourcePath)))

    @Test
    fun `never touched since V8 is not a manual edit`() {
        stubRows()
        assertFalse(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
    }

    @Test
    fun `a base-row vcsEntries snapshot that sets a checkout directory for this repository is a manual edit`() {
        stubRows(
            vcsEntriesSnapshot("ssh://h/PRJ/App.git", checkoutDirectory = null, sourcePath = null) to
                vcsEntriesSnapshot("ssh://h/PRJ/App.git", checkoutDirectory = "app", sourcePath = null),
        )
        assertTrue(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
    }

    @Test
    fun `a manual clear back to root is a manual edit, not silently re-syncable`() {
        // The regression this guards: checking only newValue (never oldValue) would see the clear's
        // all-null newValue and conclude "never set", letting Sync write TeamCity's value straight
        // back over a deliberate clear.
        stubRows(
            vcsEntriesSnapshot("ssh://h/prj/app.git", checkoutDirectory = "app", sourcePath = null) to
                vcsEntriesSnapshot("ssh://h/prj/app.git", checkoutDirectory = null, sourcePath = null),
        )
        assertTrue(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
    }

    @Test
    fun `an unrelated field changing in the same row is not a manual edit of this repository`() {
        val unchanged = vcsEntriesSnapshot("ssh://h/prj/app.git", checkoutDirectory = "app", sourcePath = null)
        stubRows(unchanged to unchanged)
        assertFalse(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
    }

    @Test
    fun `a snapshot for a different repository does not flag this one`() {
        val other = vcsEntriesSnapshot("ssh://h/prj/other.git", checkoutDirectory = null, sourcePath = null) to
            vcsEntriesSnapshot("ssh://h/prj/other.git", checkoutDirectory = "other", sourcePath = null)
        stubRows(other)
        assertFalse(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
    }

    @Test
    fun `a vcs-settings override row created under fieldOverride markerChildren is a manual edit`() {
        val created = mapOf(
            "fieldOverride[vcs.settings]" to mapOf(
                "markerChildren" to mapOf(
                    "vcsEntries" to listOf(
                        mapOf("vcsPath" to "ssh://h/prj/app.git", "checkoutDirectory" to null, "sourcePath" to "core"),
                    ),
                    "buildWorkingDirectory" to "core/app",
                ),
            ),
        )
        stubRows(emptyMap<String, Any?>() to created)
        assertTrue(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
        assertTrue(history().wasBuildWorkingDirectoryEverManuallySet(componentId))
    }

    @Test
    fun `build working directory is untouched when it stays null across a row that changes something else`() {
        val unchanged = mapOf("buildWorkingDirectory" to null)
        stubRows(unchanged to unchanged)
        assertFalse(history().wasBuildWorkingDirectoryEverManuallySet(componentId))
    }

    @Test
    fun `an unreadable V8 timestamp fails closed as manual`() {
        assertTrue(history(v8At = null).wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
        assertTrue(history(v8At = null).wasBuildWorkingDirectoryEverManuallySet(componentId))
    }
}
