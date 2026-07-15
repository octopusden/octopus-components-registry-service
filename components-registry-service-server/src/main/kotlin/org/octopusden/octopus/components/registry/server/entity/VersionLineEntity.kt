package org.octopusden.octopus.components.registry.server.entity
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

/**
 * Links a component + release [version] to a [TeamcityProjectEntity]. A component may own
 * several rows — one per distinct `PROJECT_VERSION` line ([version] is null for the single
 * default line). The referenced [teamcityProject] is shared (deduplicated by project id).
 */
@Entity
@Table(name = "version_line")
class VersionLineEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    var component: ComponentEntity,
    @Column(name = "version")
    var version: String? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teamcity_project_id", nullable = false)
    var teamcityProject: TeamcityProjectEntity,
)
