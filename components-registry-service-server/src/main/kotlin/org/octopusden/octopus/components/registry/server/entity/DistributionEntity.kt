package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

@Entity
@Table(name = "distributions")
class DistributionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id")
    var component: ComponentEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_version_id")
    var componentVersion: ComponentVersionEntity? = null,
    var explicit: Boolean = false,
    var external: Boolean = false,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var metadata: MutableMap<String, Any?> = mutableMapOf(),
    @OneToMany(mappedBy = "distribution", cascade = [CascadeType.ALL], orphanRemoval = true)
    var artifacts: MutableList<DistributionArtifactEntity> = mutableListOf(),
    @OneToMany(mappedBy = "distribution", cascade = [CascadeType.ALL], orphanRemoval = true)
    var securityGroups: MutableList<DistributionSecurityGroupEntity> = mutableListOf(),
)
