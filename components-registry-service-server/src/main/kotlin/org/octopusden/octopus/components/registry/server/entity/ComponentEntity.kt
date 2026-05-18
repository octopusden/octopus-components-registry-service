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
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/**
 * Schema v2 — top-level component entity.
 *
 * **Entity conventions (apply to all classes under the `entity` package):**
 *
 *   - **Cascade default:** `cascade = [CascadeType.ALL]`, `orphanRemoval = true`
 *     on `@OneToMany` collections that are parent-owned (e.g., child rows of
 *     `components` and `component_configurations`). No cascade on dictionary
 *     M:N junctions — those are managed independently of the parent lifecycle.
 *
 *   - **Fetch default:** `FetchType.LAZY` everywhere (both `@ManyToOne` and
 *     `@OneToMany`). Hot-path repository methods opt in to eager loading via
 *     `@EntityGraph` rather than annotating the field.
 *
 *   - **`@Version`:** only on the top-level aggregate root (`ComponentEntity`).
 *     Sub-entities (`ComponentConfigurationEntity`, child rows, dictionary
 *     junctions) do not carry their own version column. A concurrent edit to
 *     any child increments `ComponentEntity.version` via the service layer.
 *
 *   - **M:N junctions:** modeled as standalone `@Entity` classes with `@IdClass`
 *     composite keys (e.g., `ComponentLabelEntity`), not as `@ManyToMany` with
 *     `@JoinTable`. This keeps junction rows independently queryable and lets
 *     future audit columns drop in without remodelling the association.
 *
 *   - **Naming:** rely on the Hibernate default `PhysicalNamingStrategyStandardImpl`
 *     for entity-class → table name. Use explicit `@Column(name = "...")` on
 *     every column whose snake_case name doesn't trivially map from the Kotlin
 *     camelCase field. When in doubt, set the column name explicitly.
 *
 *   - **Soft references:** `dependency_mappings.component_key`,
 *     `component_source.component_key`, `component_doc_links.doc_component_key`
 *     stay as strings (no `@ManyToOne`). Resolution happens in the service
 *     layer because targets may be archived or live in a different installation.
 *
 *   - **Bidirectional collections:** parent-side `@OneToMany` lists for child
 *     entities (`component_configurations`, `component_artifact_ids`,
 *     `component_labels`, etc.) are added in Phase 2.5 after all child entity
 *     classes exist. Phase 2.0–2.4 keep one-way `@ManyToOne` references from
 *     the child side only.
 */
@Entity
@Table(name = "components")
class ComponentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(name = "component_key", nullable = false, unique = true)
    var componentKey: String = "",

    @Column(name = "component_owner")
    var componentOwner: String? = null,

    @Column(name = "display_name")
    var displayName: String? = null,

    @Column(name = "product_type", length = 20)
    var productType: String? = null,

    @Column(name = "client_code")
    var clientCode: String? = null,

    @Column(name = "archived", nullable = false)
    var archived: Boolean = false,

    @Column(name = "solution")
    var solution: Boolean? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_component_id")
    var parentComponent: ComponentEntity? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_group_id")
    var componentGroup: ComponentGroupEntity? = null,

    @Column(name = "release_manager")
    var releaseManager: String? = null,

    @Column(name = "security_champion")
    var securityChampion: String? = null,

    @Column(name = "copyright", columnDefinition = "TEXT")
    var copyright: String? = null,

    @Column(name = "releases_in_default_branch")
    var releasesInDefaultBranch: Boolean? = null,

    @Column(name = "jira_display_name")
    var jiraDisplayName: String? = null,

    @Column(name = "jira_hotfix_version_format")
    var jiraHotfixVersionFormat: String? = null,

    @Column(name = "vcs_external_registry", columnDefinition = "TEXT")
    var vcsExternalRegistry: String? = null,

    @Column(name = "distribution_explicit")
    var distributionExplicit: Boolean? = null,

    @Column(name = "distribution_external")
    var distributionExternal: Boolean? = null,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    // --- Bidirectional collections (parent-owned children) ---

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var configurations: MutableList<ComponentConfigurationEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var artifactIds: MutableList<ComponentArtifactIdEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var securityGroups: MutableList<DistributionSecurityGroupEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var teamcityProjects: MutableList<ComponentTeamcityProjectEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var docLinks: MutableList<ComponentDocLinkEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", fetch = FetchType.LAZY)
    var labelJunctions: MutableList<ComponentLabelEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", fetch = FetchType.LAZY)
    var systemJunctions: MutableList<ComponentSystemEntity> = mutableListOf(),
)
