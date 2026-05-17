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
 * `flavor` is the build variant (e.g. "amazon"), NOT a Docker registry tag.
 * Tags are versions like `1.2.3` and live on the request side, not here.
 */
@Entity
@Table(name = "distribution_docker_images")
class DistributionDockerImageEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_configuration_id", nullable = false)
    var componentConfiguration: ComponentConfigurationEntity,

    @Column(name = "image_name", columnDefinition = "TEXT", nullable = false)
    var imageName: String = "",

    @Column(name = "flavor")
    var flavor: String? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)
