package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Schema v2 — alias → component_key lookup table.
 *
 * Differs from the old entity: primary key is now `alias` (was a surrogate
 * before), and the target column renamed `component_name` → `component_key`.
 * Both columns are plain strings; `component_key` is a soft reference to
 * `components.component_key` resolved in the service layer.
 *
 * See `ComponentEntity` kdoc for the cross-cutting v2 entity conventions.
 */
@Entity
@Table(name = "dependency_mappings")
class DependencyMappingEntity(
    @Id
    @Column(name = "alias", length = 255)
    var alias: String = "",

    @Column(name = "component_key", nullable = false, length = 255)
    var componentKey: String = "",
)
