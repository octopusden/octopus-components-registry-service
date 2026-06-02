package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * Schema v2 — append-only audit trail row.
 *
 * Differs from the old entity: `old_value` / `new_value` / `change_diff` are now
 * plain `TEXT` columns at the DDL level (no longer `jsonb`), but the JSON shape
 * is preserved via `@JdbcTypeCode(SqlTypes.JSON)` so Hibernate continues to
 * serialize/deserialize `Map<String, Any?>` payloads.
 *
 * See `ComponentEntity` kdoc for the cross-cutting v2 entity conventions.
 */
@Entity
@Table(name = "audit_log")
class AuditLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "entity_type", nullable = false, length = 50)
    var entityType: String = "",

    @Column(name = "entity_id", nullable = false)
    var entityId: String = "",

    @Column(name = "action", nullable = false, length = 20)
    var action: String = "",

    @Column(name = "changed_by")
    var changedBy: String? = null,

    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.now(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "TEXT")
    var oldValue: Map<String, Any?>? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "TEXT")
    var newValue: Map<String, Any?>? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_diff", columnDefinition = "TEXT")
    var changeDiff: Map<String, Any?>? = null,

    @Column(name = "correlation_id")
    var correlationId: String? = null,

    @Column(name = "source", nullable = false, length = 20)
    var source: String = "api",
) {
    companion object {
        /**
         * Audit action assigned to a component's first appearance during the
         * git-history backfill. Distinct from the runtime `CREATE` action so the
         * audit views can hide migration noise by default (SYS-049). Other actions
         * (CREATE/UPDATE/DELETE/RENAME) remain inline literals at their write
         * sites — only MIGRATED needs to be shared between the writer
         * (`GitHistoryImportServiceImpl`) and the query filter (`AuditServiceImpl`).
         */
        const val ACTION_MIGRATED = "MIGRATED"
    }
}
