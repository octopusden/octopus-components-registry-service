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
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/**
 * Schema v2 — wide typed per-(component, version_range) row.
 *
 * Four row shapes. `rowType` is the source-of-truth classifier; `overriddenAttribute`
 * is the payload discriminator for SCALAR_OVERRIDE/MARKER and MUST be NULL for
 * BASE/RANGE_PRESENCE. DB-level CHECKs enforce the row_type ↔ overridden_attribute
 * pairing and the all-typed-cols-NULL rule for MARKER + RANGE_PRESENCE.
 *
 *   1. **BASE** (`rowType = "BASE"`, `overriddenAttribute IS NULL`): the
 *      component's effective default at `ALL_VERSIONS`. Typed columns carry the
 *      default values; a partial UNIQUE index ensures at most one base row per
 *      component. Under the decoupled version model (ADR-018) the base is ALWAYS
 *      at `ALL_VERSIONS` and coverage lives in separate RANGE_PRESENCE rows;
 *      `isSyntheticBase` is **vestigial — always false** (see the column doc),
 *      retained only for v4-contract stability.
 *
 *   2. **SCALAR_OVERRIDE** (`rowType = "SCALAR_OVERRIDE"`,
 *      `overriddenAttribute = '<aspect.field>'`, e.g., `build.javaVersion`,
 *      `escrow.generation`, `jira.projectKey`). Exactly one typed column
 *      non-NULL — the column matching the attribute path. No attached child
 *      rows. The DB CHECK forbids reusing a marker name here; the
 *      "exactly one non-NULL" rule is enforced in the service layer.
 *
 *   3. **MARKER** (`rowType = "MARKER"`, `overriddenAttribute` is one of the
 *      six marker names — see schema-spec.md §3.3). All typed columns are
 *      NULL — DB CHECK enforces this. Child rows carry the data via FK back
 *      to this row.
 *
 *   4. **RANGE_PRESENCE** (`rowType = "RANGE_PRESENCE"`,
 *      `overriddenAttribute IS NULL`, all typed columns NULL): storage
 *      artifact that marks a DSL `componentVersion(R)` block exists for the
 *      given range so the resolver enumerates it even when the range's
 *      scalars/markers match base. Hidden from V4 editor APIs.
 *
 * See `ComponentEntity` kdoc for the cross-cutting v2 entity conventions.
 */
@Entity
@Table(
    name = "component_configurations",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_component_configurations_component_range_attribute",
            columnNames = ["component_id", "version_range", "overridden_attribute"],
        ),
    ],
)
class ComponentConfigurationEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    var component: ComponentEntity,

    @Column(name = "version_range", nullable = false)
    var versionRange: String = "",

    @Column(name = "overridden_attribute", length = 50)
    var overriddenAttribute: String? = null,

    @Column(name = "row_type", length = 32, nullable = false)
    var rowType: String,

    // Vestigial under ADR-018 (decoupled version model): always false. The
    // pre-ADR-018 importer set it true to mark a synthetic-bounded base; that base
    // no longer exists (the base is always ALL_VERSIONS, coverage lives in
    // RANGE_PRESENCE rows). Retained as a NOT-NULL column / DTO field for
    // v4-contract stability only — consumers must not infer coverage from it.
    @Column(name = "is_synthetic_base", nullable = false)
    var isSyntheticBase: Boolean = false,

    // --- BUILD aspect ---
    @Column(name = "build_system", length = 50)
    var buildSystem: String? = null,

    @Column(name = "java_version", length = 50)
    var javaVersion: String? = null,

    @Column(name = "maven_version", length = 50)
    var mavenVersion: String? = null,

    @Column(name = "gradle_version", length = 50)
    var gradleVersion: String? = null,

    @Column(name = "build_file_path", columnDefinition = "TEXT")
    var buildFilePath: String? = null,

    @Column(name = "deprecated")
    var deprecated: Boolean? = null,

    @Column(name = "required_project")
    var requiredProject: Boolean? = null,

    @Column(name = "project_version")
    var projectVersion: String? = null,

    @Column(name = "system_properties", columnDefinition = "TEXT")
    var systemProperties: String? = null,

    @Column(name = "build_tasks", columnDefinition = "TEXT")
    var buildTasks: String? = null,

    @Column(name = "escrow_build_task", columnDefinition = "TEXT")
    var escrowBuildTask: String? = null,

    // --- ESCROW aspect ---
    @Column(name = "escrow_provided_dependencies", columnDefinition = "TEXT")
    var escrowProvidedDependencies: String? = null,

    @Column(name = "escrow_reusable")
    var escrowReusable: Boolean? = null,

    @Column(name = "escrow_generation", length = 50)
    var escrowGeneration: String? = null,

    @Column(name = "escrow_disk_space", length = 50)
    var escrowDiskSpace: String? = null,

    @Column(name = "escrow_additional_sources", columnDefinition = "TEXT")
    var escrowAdditionalSources: String? = null,

    @Column(name = "escrow_gradle_include_configurations", columnDefinition = "TEXT")
    var escrowGradleIncludeConfigurations: String? = null,

    @Column(name = "escrow_gradle_exclude_configurations", columnDefinition = "TEXT")
    var escrowGradleExcludeConfigurations: String? = null,

    @Column(name = "escrow_gradle_include_test_configurations")
    var escrowGradleIncludeTestConfigurations: Boolean? = null,

    // --- JIRA aspect (per-version overridable fields) ---
    @Column(name = "jira_project_key", length = 50)
    var jiraProjectKey: String? = null,

    @Column(name = "jira_technical")
    var jiraTechnical: Boolean? = null,

    @Column(name = "jira_minor_version_format")
    var jiraMinorVersionFormat: String? = null,

    @Column(name = "jira_release_version_format")
    var jiraReleaseVersionFormat: String? = null,

    @Column(name = "jira_build_version_format")
    var jiraBuildVersionFormat: String? = null,

    @Column(name = "jira_line_version_format")
    var jiraLineVersionFormat: String? = null,

    @Column(name = "jira_version_prefix")
    var jiraVersionPrefix: String? = null,

    @Column(name = "jira_version_format")
    var jiraVersionFormat: String? = null,

    /**
     * Per-range override for `jira.componentVersionFormat.hotfixVersionFormat`.
     * Sibling column to `jira_release_version_format` etc. The per-component
     * base value (Defaults / top-level DSL) lives on
     * `components.jira_hotfix_version_format`; the resolver layers this column
     * on top when the row applies for the requested version range.
     */
    @Column(name = "jira_hotfix_version_format")
    var jiraHotfixVersionFormat: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    // --- Bidirectional collections (children of this configuration row;
    //     populated when overridden_attribute is a marker, or part of base) ---

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var vcsEntries: MutableList<VcsSettingsEntryEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var mavenArtifacts: MutableList<DistributionMavenArtifactEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var fileUrlArtifacts: MutableList<DistributionFileUrlArtifactEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var dockerImages: MutableList<DistributionDockerImageEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var packages: MutableList<DistributionPackageEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var requiredToolJunctions: MutableList<ComponentRequiredToolEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var buildToolBeans: MutableList<ComponentBuildToolBeanEntity> = mutableListOf(),
)
