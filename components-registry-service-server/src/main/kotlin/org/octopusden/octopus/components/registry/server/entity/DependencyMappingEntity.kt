package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "dependency_mappings")
class DependencyMappingEntity(
    @Id
    var alias: String = "",
    @Column(name = "component_name", nullable = false)
    var componentName: String = "",
)
