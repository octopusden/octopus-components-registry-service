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

@Entity
@Table(name = "audit_log")
class AuditLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "entity_type", nullable = false)
    var entityType: String = "",
    @Column(name = "entity_id", nullable = false)
    var entityId: String = "",
    @Column(nullable = false)
    var action: String = "",
    @Column(name = "changed_by")
    var changedBy: String? = null,
    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.now(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    var oldValue: Map<String, Any?>? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    var newValue: Map<String, Any?>? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_diff", columnDefinition = "jsonb")
    var changeDiff: Map<String, Any?>? = null,
    @Column(name = "correlation_id")
    var correlationId: String? = null,
)
