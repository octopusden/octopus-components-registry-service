package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * A distinct TeamCity project, keyed by its TeamCity `project_id` string. `project_id` is
 * UNIQUE — one row per TC project, referenced by any number of [VersionLineEntity] rows.
 * The web URL is not stored; it is composed of `project_id` at read time.
 */
@Entity
@Table(name = "teamcity_project")
class TeamcityProjectEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(name = "project_id", nullable = false, unique = true)
    var projectId: String = "",
)
