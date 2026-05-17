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

/** `packageType` is `"DEB"` or `"RPM"`. Validated at the service layer. */
@Entity
@Table(name = "distribution_packages")
class DistributionPackageEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_configuration_id", nullable = false)
    var componentConfiguration: ComponentConfigurationEntity,

    @Column(name = "package_type", length = 10, nullable = false)
    var packageType: String = "",

    @Column(name = "package_name", nullable = false)
    var packageName: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)
