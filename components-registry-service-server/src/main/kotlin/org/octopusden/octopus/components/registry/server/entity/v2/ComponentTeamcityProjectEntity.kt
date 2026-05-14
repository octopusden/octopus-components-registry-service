package org.octopusden.octopus.components.registry.server.entity.v2

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "component_teamcity_projects")
class ComponentTeamcityProjectEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    var component: ComponentEntity,

    @Column(name = "project_id", nullable = false)
    var projectId: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)
