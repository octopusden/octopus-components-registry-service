package org.octopusden.octopus.components.registry.server.event

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository

/**
 * Bug fix: a component "save" that changes nothing used to write an empty
 * audit row (action=UPDATE, changeDiff=null). The listener now suppresses
 * no-op updates — a row whose `oldValue`/`newValue` are both present but
 * carry no field-level difference is dropped — while still recording CREATE
 * (null oldValue) and DELETE (real diff). This keeps the audit log free of
 * meaningless "changed nothing" entries regardless of which write path
 * published the event (component update, field-override update, TeamCity sync).
 *
 * Pure Mockito test — no Spring context, no DB.
 */
@Timeout(10, unit = TimeUnit.SECONDS)
class AuditEventListenerTest {
    private lateinit var auditLogRepository: AuditLogRepository
    private lateinit var listener: AuditEventListener

    @BeforeEach
    fun setUp() {
        auditLogRepository = mock(AuditLogRepository::class.java)
        listener = AuditEventListener(auditLogRepository)
    }

    @Test
    @DisplayName("SYS-048: no-op UPDATE (both snapshots present, empty diff) is NOT persisted")
    fun `SYS-048 no-op UPDATE writes no audit row`() {
        listener.handleAuditEvent(
            AuditEvent(
                entityType = "Component",
                entityId = "c1",
                action = "UPDATE",
                oldValue = mapOf("displayName" to "Widget", "owner" to "alice"),
                newValue = mapOf("displayName" to "Widget", "owner" to "alice"),
            ),
        )

        verify(auditLogRepository, never()).save(any())
    }

    @Test
    @DisplayName("SYS-048: real UPDATE (non-empty diff) is persisted with the computed changeDiff")
    fun `SYS-048 real UPDATE is persisted`() {
        listener.handleAuditEvent(
            AuditEvent(
                entityType = "Component",
                entityId = "c1",
                action = "UPDATE",
                oldValue = mapOf("displayName" to "Widget"),
                newValue = mapOf("displayName" to "Gadget"),
            ),
        )

        val captor = ArgumentCaptor.forClass(AuditLogEntity::class.java)
        verify(auditLogRepository).save(captor.capture())
        assertEquals("UPDATE", captor.value.action)
        assertEquals(
            mapOf("displayName" to mapOf("old" to "Widget", "new" to "Gadget")),
            captor.value.changeDiff,
        )
    }

    @Test
    @DisplayName("SYS-048: CREATE (null oldValue) is always persisted even though the diff is null")
    fun `SYS-048 CREATE is always persisted`() {
        listener.handleAuditEvent(
            AuditEvent(
                entityType = "Component",
                entityId = "c1",
                action = "CREATE",
                oldValue = null,
                newValue = mapOf("displayName" to "Widget"),
            ),
        )

        val captor = ArgumentCaptor.forClass(AuditLogEntity::class.java)
        verify(auditLogRepository).save(captor.capture())
        assertEquals("CREATE", captor.value.action)
        assertNull(captor.value.changeDiff, "CREATE has no oldValue, so AuditDiff yields null — but the row must still be saved")
    }

    @Test
    @DisplayName("SYS-048: DELETE (both snapshots present, real diff) is persisted")
    fun `SYS-048 DELETE is persisted`() {
        listener.handleAuditEvent(
            AuditEvent(
                entityType = "Component",
                entityId = "c1",
                action = "DELETE",
                oldValue = mapOf("name" to "Widget", "archived" to false),
                newValue = mapOf("name" to "Widget", "archived" to true),
            ),
        )

        verify(auditLogRepository).save(any())
    }
}
