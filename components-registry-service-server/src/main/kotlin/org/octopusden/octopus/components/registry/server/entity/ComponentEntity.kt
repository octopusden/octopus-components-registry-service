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
import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/**
 * IN-clause batch size for the LAZY associations on the schema-v2 entities. Sized
 * comfortably above the production component count so the resolver read paths load each
 * collection role (and lazy to-one proxy) in a single `… IN (…)` select. See the
 * `@BatchSize` rationale in [ComponentEntity]'s kdoc. File-scoped so both the class-level
 * (`@ManyToOne` target) and field-level (`@OneToMany`) annotations can reference it.
 */
internal const val BATCH_FETCH_SIZE = 100

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
 *     `@OneToMany`). A hot-path read that needs a *single* collection eager can
 *     opt in via `@EntityGraph` on the repository method. But the resolver read
 *     paths (`find-by-artifacts`, `find-by-docker-images`) walk *several* bag
 *     (`List`) collections per component, and a single `@EntityGraph`/fetch-join
 *     cannot eager-fetch more than one bag at once — Hibernate throws
 *     `MultipleBagFetchException`. Those collections therefore opt into IN-clause
 *     batch loading via `@BatchSize` instead, which loads each role in its own
 *     `WHERE parent_id IN (…)` select (immune to the multi-bag restriction) and
 *     turns the per-component N+1 into a bounded number of batched selects.
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
// Class-level @BatchSize governs batched initialization of LAZY `@ManyToOne` proxies that
// TARGET this entity (e.g. the self-referential `parentComponent`) — Hibernate reads the
// to-one batch size from the target class, NOT from the referencing field, so a field-level
// @BatchSize on `parentComponent` would be silently ignored. The `@OneToMany` bags below
// carry their own field-level @BatchSize (that placement IS honoured for collections).
@BatchSize(size = BATCH_FETCH_SIZE)
class ComponentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(name = "component_key", nullable = false, unique = true)
    var componentKey: String = "",

    @Column(name = "component_owner")
    var componentOwner: String? = null,

    // Nullable + unique (schema-spec). NULL when no componentDisplayName is declared —
    // this preserves the legacy v1/v2/v3 wire `$.name` (prod 2.0.87 served null for unnamed
    // components, so we do NOT backfill the key). UNIQUE applies to non-null values only
    // (Postgres allows multiple NULLs). The import stores the DSL value verbatim and fails
    // fast on duplicate non-null names; the create/update API enforces uniqueness and (for
    // explicit+external components only) requiredness, mirroring the DSL validator.
    @Column(name = "display_name", unique = true)
    var displayName: String? = null,

    @Column(name = "product_type", length = 20)
    var productType: String? = null,

    @Column(name = "client_code")
    var clientCode: String? = null,

    @Column(name = "archived", nullable = false)
    var archived: Boolean = false,

    @Column(name = "solution")
    var solution: Boolean? = null,

    // Batched via the class-level @BatchSize on the TARGET entities (ComponentEntity for
    // parentComponent, ComponentGroupEntity for componentGroup) — to-one batch size is read
    // from the target class, not the field, so no field-level @BatchSize here.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_component_id")
    var parentComponent: ComponentEntity? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_group_id")
    var componentGroup: ComponentGroupEntity? = null,

    // True when this component is referenced as a `parentComponent` by at least one
    // other component — i.e. it is eligible to be picked as a parent. This is the flat
    // peer-reference relationship and is INDEPENDENT of aggregator status: an
    // aggregator owns a `components { }` block and forms a ComponentGroup
    // (`componentGroup` above), a separate concept. Seeded by the Groovy import (sets
    // true for DSL-referenced parents, never false) and editable via the v4 API. A
    // component with `canBeParent = true` may not itself have a `parentComponent`
    // (enforced in the service layer) — single-level: a parent cannot have a parent.
    @Column(name = "can_be_parent", nullable = false)
    var canBeParent: Boolean = false,

    // releaseManager / securityChampion are no longer scalar columns — they
    // moved to the ordered child collections below (releaseManagers /
    // securityChampions). componentOwner stays a single-value scalar.

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

    // Scalar FK to `systems(code)`. Collapsed from the M:N junction
    // `component_systems` in this iteration — a component now belongs to
    // at most one system. Nullable on the schema; service-layer rejects
    // a non-blank value that is not in the master `systems` table.
    @Column(name = "system_code", length = 50)
    var systemCode: String? = null,

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
    @BatchSize(size = BATCH_FETCH_SIZE)
    var configurations: MutableList<ComponentConfigurationEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var artifactIds: MutableList<ComponentArtifactIdEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var securityGroups: MutableList<DistributionSecurityGroupEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var teamcityProjects: MutableList<ComponentTeamcityProjectEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var docLinks: MutableList<ComponentDocLinkEntity> = mutableListOf(),

    // Ordered multi-value people. No `@OrderBy` — sort by `sortOrder` in the
    // accessors / mappers (matching the artifactIds / docLinks convention).
    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var releaseManagers: MutableList<ComponentReleaseManagerEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var securityChampions: MutableList<ComponentSecurityChampionEntity> = mutableListOf(),

    @OneToMany(mappedBy = "component", fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var labelJunctions: MutableList<ComponentLabelEntity> = mutableListOf(),
) {
    /** Ordered release-manager usernames (first = primary). */
    fun releaseManagerUsernames(): List<String> =
        releaseManagers.sortedBy { it.sortOrder }.map { it.username }

    /**
     * Replace the whole ordered release-manager list. Single canonicalization
     * point for every write path (create / patch / import): trim → drop blanks
     * → keep-first dedupe, then clear()+re-add child rows with `sortOrder =
     * index` (the orphanRemoval clear/re-add pattern used for `artifactIds`).
     */
    fun replaceReleaseManagerUsernames(usernames: List<String>) {
        val canonical = canonicalizeUsernames(usernames)
        releaseManagers.clear()
        canonical.forEachIndexed { index, username ->
            releaseManagers.add(
                ComponentReleaseManagerEntity(component = this, username = username, sortOrder = index),
            )
        }
    }

    /** Ordered security-champion usernames (first = primary). */
    fun securityChampionUsernames(): List<String> =
        securityChampions.sortedBy { it.sortOrder }.map { it.username }

    /** Replace the whole ordered security-champion list (same canonical form as RM). */
    fun replaceSecurityChampionUsernames(usernames: List<String>) {
        val canonical = canonicalizeUsernames(usernames)
        securityChampions.clear()
        canonical.forEachIndexed { index, username ->
            securityChampions.add(
                ComponentSecurityChampionEntity(component = this, username = username, sortOrder = index),
            )
        }
    }

    private companion object {
        /** trim → drop blank → keep-first dedupe, order preserved. */
        fun canonicalizeUsernames(usernames: List<String>): List<String> {
            val seen = LinkedHashSet<String>()
            usernames.forEach { raw ->
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) seen.add(trimmed)
            }
            return seen.toList()
        }
    }
}
