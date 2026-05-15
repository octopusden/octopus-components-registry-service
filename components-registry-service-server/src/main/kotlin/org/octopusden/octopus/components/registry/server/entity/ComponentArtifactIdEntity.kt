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

@Entity
@Table(name = "component_artifact_ids")
class ComponentArtifactIdEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    var component: ComponentEntity,

    @Column(name = "group_pattern", columnDefinition = "TEXT", nullable = false)
    var groupPattern: String = "",

    @Column(name = "artifact_pattern", columnDefinition = "TEXT", nullable = false)
    var artifactPattern: String = "",
)
