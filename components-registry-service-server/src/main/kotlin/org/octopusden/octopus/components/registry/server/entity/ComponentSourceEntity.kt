package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Schema v2 — per-component source-of-truth marker (git vs db).
 *
 * Differs from the old entity: primary key renamed `component_name` →
 * `component_key`, aligning naming with the rest of v2 (soft reference to
 * `components.component_key`; no JPA association — see `ComponentEntity` kdoc).
 *
 * See `ComponentEntity` kdoc for the cross-cutting v2 entity conventions.
 */
@Entity
@Table(name = "component_source")
class ComponentSourceEntity(
    @Id
    @Column(name = "component_key", length = 255)
    var componentKey: String = "",
    @Column(name = "source", nullable = false, length = 10)
    var source: String = "git",
    @Column(name = "migrated_at")
    var migratedAt: Instant? = null,
    @Column(name = "migrated_by")
    var migratedBy: String? = null,
)
