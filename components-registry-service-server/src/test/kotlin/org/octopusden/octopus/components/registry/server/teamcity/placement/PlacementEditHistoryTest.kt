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

    private fun stubRows(vararg newValues: Map<String, Any?>) {
        whenever(
            auditLogRepository.findByEntityTypeAndEntityIdAndChangedAtAfterAndActionIn(
                "Component",
                componentId.toString(),
                v8AppliedAt,
                listOf("UPDATE", "RENAME"),
            ),
        ).thenReturn(
            newValues.map { AuditLogEntity(entityType = "Component", entityId = componentId.toString(), action = "UPDATE", newValue = it) },
        )
    }

    @Test
    fun `never touched since V8 is not a manual edit`() {
        stubRows()
        assertFalse(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
    }

    @Test
    fun `a base-row vcsEntries snapshot with a checkout directory for this repository is a manual edit`() {
        stubRows(
            mapOf(
                "vcsEntries" to listOf(
                    mapOf("vcsPath" to "ssh://h/PRJ/App.git", "checkoutDirectory" to "app", "sourcePath" to null),
                ),
            ),
        )
        assertTrue(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
    }

    @Test
    fun `a snapshot for a different repository does not flag this one`() {
        stubRows(
            mapOf(
                "vcsEntries" to listOf(
                    mapOf("vcsPath" to "ssh://h/prj/other.git", "checkoutDirectory" to "other", "sourcePath" to null),
                ),
            ),
        )
        assertFalse(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
    }

    @Test
    fun `a vcs-settings override-row snapshot is found under fieldOverride markerChildren`() {
        stubRows(
            mapOf(
                "fieldOverride[vcs.settings]" to mapOf(
                    "markerChildren" to mapOf(
                        "vcsEntries" to listOf(
                            mapOf("vcsPath" to "ssh://h/prj/app.git", "checkoutDirectory" to null, "sourcePath" to "core"),
                        ),
                        "buildWorkingDirectory" to "core/app",
                    ),
                ),
            ),
        )
        assertTrue(history().wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
        assertTrue(history().wasBuildWorkingDirectoryEverManuallySet(componentId))
    }

    @Test
    fun `build working directory is untouched when no snapshot ever carried a non-null value`() {
        stubRows(mapOf("buildWorkingDirectory" to null))
        assertFalse(history().wasBuildWorkingDirectoryEverManuallySet(componentId))
    }

    @Test
    fun `an unreadable V8 timestamp fails closed as manual`() {
        assertTrue(history(v8At = null).wasEverManuallyPlaced(componentId, "ssh://h/prj/app.git"))
        assertTrue(history(v8At = null).wasBuildWorkingDirectoryEverManuallySet(componentId))
    }
}
