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
 * `distribution_security_groups` is a child of `components` (not configurations) —
 * per-component policy that never varies per version range.
 *
 * `group_type` is `"read"` or `"write"` (default `"read"`).
 */
@Entity
@Table(name = "distribution_security_groups")
class DistributionSecurityGroupEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    var component: ComponentEntity,

    @Column(name = "group_type", length = 20, nullable = false)
    var groupType: String = "read",

    @Column(name = "group_name", nullable = false)
    var groupName: String = "",
)
