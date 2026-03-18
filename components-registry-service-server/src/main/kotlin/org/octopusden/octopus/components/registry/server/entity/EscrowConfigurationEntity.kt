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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

@Entity
@Table(name = "escrow_configurations")
class EscrowConfigurationEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id")
    var component: ComponentEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_version_id")
    var componentVersion: ComponentVersionEntity? = null,
    // see TD-002
    @Column(name = "build_task", columnDefinition = "TEXT")
    var buildTask: String? = null,
    @Column(name = "provided_dependencies", columnDefinition = "TEXT")
    var providedDependencies: String? = null,
    var reusable: Boolean? = null,
    var generation: String? = null,
    @Column(name = "disk_space")
    var diskSpace: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var metadata: MutableMap<String, Any?> = mutableMapOf(),
)
