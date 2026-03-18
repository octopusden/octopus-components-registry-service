package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "component_source")
class ComponentSourceEntity(
    @Id
    @Column(name = "component_name")
    var componentName: String = "",
    @Column(nullable = false)
    var source: String = "git",
    @Column(name = "migrated_at")
    var migratedAt: Instant? = null,
    @Column(name = "migrated_by")
    var migratedBy: String? = null,
)
