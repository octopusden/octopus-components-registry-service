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
    // SYS-039: §7.0 Wave 2 PR-G fields. All nullable / empty-default so
    // existing rows survive the V6 migration without rewrite. Portal-side
    // visibility/required is field-config controlled per ADR-011.
    @Column(name = "group_id")
    var groupId: String? = null,
    @Column(name = "release_manager")
    var releaseManager: String? = null,
    @Column(name = "security_champion")
    var securityChampion: String? = null,
    @Column(columnDefinition = "TEXT")
    var copyright: String? = null,
    @Column(name = "releases_in_default_branch")
    var releasesInDefaultBranch: Boolean? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    var labels: Array<String> = emptyArray(),
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
    // SYS-040: V4 summary mapper reads firstOrNull() on these collections
    // for list-view badges/links. We considered @OrderBy("id ASC") for
    // deterministic first-pick, but adding it broke V2/V3 contract tests
    // (RES-001: All Jira component version ranges) — the V2/V3 code path
    // also calls vcsSettings.firstOrNull() and committed expected-data
    // fixtures encode the previous (insertion-order) first-pick. Reverted
    // until either the test fixtures are migrated or the child tables
    // grow a created_at column for proper creation-order @OrderBy.
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
