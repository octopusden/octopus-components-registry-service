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
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "components")
class ComponentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false, unique = true)
    var name: String = "",
    @Column(name = "component_owner")
    var componentOwner: String? = null,
    @Column(name = "display_name")
    var displayName: String? = null,
    @Column(name = "product_type")
    var productType: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    var system: Array<String> = emptyArray(),
    @Column(name = "client_code")
    var clientCode: String? = null,
    var archived: Boolean = false,
    var solution: Boolean? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_component_id")
    var parentComponent: ComponentEntity? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var metadata: MutableMap<String, Any?> = mutableMapOf(),
    @Version
    var version: Long = 0,
    @CreationTimestamp
    @Column(updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp
    var updatedAt: Instant? = null,
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    var versions: MutableList<ComponentVersionEntity> = mutableListOf(),
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    var buildConfigurations: MutableList<BuildConfigurationEntity> = mutableListOf(),
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    var escrowConfigurations: MutableList<EscrowConfigurationEntity> = mutableListOf(),
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    var vcsSettings: MutableList<VcsSettingsEntity> = mutableListOf(),
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    var distributions: MutableList<DistributionEntity> = mutableListOf(),
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    var jiraComponentConfigs: MutableList<JiraComponentConfigEntity> = mutableListOf(),
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true)
    var artifactIds: MutableList<ComponentArtifactIdEntity> = mutableListOf(),
)
