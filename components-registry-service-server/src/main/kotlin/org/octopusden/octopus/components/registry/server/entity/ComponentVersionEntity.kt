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
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "component_versions")
class ComponentVersionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id", nullable = false)
    var component: ComponentEntity? = null,
    @Column(name = "version_range", nullable = false)
    var versionRange: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var metadata: MutableMap<String, Any?> = mutableMapOf(),
    @CreationTimestamp
    @Column(updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp
    var updatedAt: Instant? = null,
    @OneToMany(mappedBy = "componentVersion", cascade = [CascadeType.ALL], orphanRemoval = true)
    var buildConfigurations: MutableList<BuildConfigurationEntity> = mutableListOf(),
    @OneToMany(mappedBy = "componentVersion", cascade = [CascadeType.ALL], orphanRemoval = true)
    var escrowConfigurations: MutableList<EscrowConfigurationEntity> = mutableListOf(),
    @OneToMany(mappedBy = "componentVersion", cascade = [CascadeType.ALL], orphanRemoval = true)
    var vcsSettings: MutableList<VcsSettingsEntity> = mutableListOf(),
    @OneToMany(mappedBy = "componentVersion", cascade = [CascadeType.ALL], orphanRemoval = true)
    var distributions: MutableList<DistributionEntity> = mutableListOf(),
    @OneToMany(mappedBy = "componentVersion", cascade = [CascadeType.ALL], orphanRemoval = true)
    var jiraComponentConfigs: MutableList<JiraComponentConfigEntity> = mutableListOf(),
    @OneToMany(mappedBy = "componentVersion", cascade = [CascadeType.ALL], orphanRemoval = true)
    var artifactIds: MutableSet<ComponentArtifactIdEntity> = mutableSetOf(),
)
