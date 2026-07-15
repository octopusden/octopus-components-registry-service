package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * Schema v2 — single-row-per-key registry configuration store.
 *
 * Differs from the old entity: `value` is now declared as `TEXT NOT NULL` in the
 * DDL (no longer `jsonb`). JSON shape is preserved via `@JdbcTypeCode(SqlTypes.JSON)`.
 *
 * See `ComponentEntity` kdoc for the cross-cutting v2 entity conventions.
 */
@Entity
@Table(name = "registry_config")
class RegistryConfigEntity(
    @Id
    @Column(name = "key", length = 255)
    var key: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", columnDefinition = "TEXT", nullable = false)
    var value: Map<String, Any?> = emptyMap(),
    @Column(name = "updated_by")
    var updatedBy: String? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
