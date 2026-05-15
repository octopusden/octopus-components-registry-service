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
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/**
 * Schema v2 — wide typed per-(component, version_range) row.
 *
 * Three row shapes (enforced by service-layer validation + targeted DB CHECKs):
 *
 *   1. **Base row**: `overriddenAttribute IS NULL`, typed columns may carry the
 *      component's default values. Partial UNIQUE index ensures at most one
 *      base row per component. `isSyntheticBase = true` indicates a base
 *      populated purely from `Defaults.groovy` — legacy variants-Map mapping
 *      skips synthetic bases (MIG-029 structural fix), but single-version
 *      resolve still uses the base as fallback.
 *
 *   2. **Scalar override row**: `overriddenAttribute = '<aspect.field>'`
 *      (e.g., `build.javaVersion`, `escrow.generation`, `jira.projectKey`).
 *      Exactly one typed column non-NULL — the column matching the attribute
 *      path. No attached child rows. Enforced in the service layer.
 *
 *   3. **Marker row**: `overriddenAttribute` is one of the marker names
 *      (see schema-spec.md §3.3). All typed columns are NULL — DB CHECK
 *      constraints enforce this. Child rows carry the data via FK back to
 *      this row.
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

    @Column(name = "is_synthetic_base", nullable = false)
    var isSyntheticBase: Boolean = false,

    // --- BUILD aspect ---
    @Column(name = "build_system", length = 50)
    var buildSystem: String? = null,

    @Column(name = "build_system_version", length = 50)
    var buildSystemVersion: String? = null,

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

    @Column(name = "jira_major_version_format")
    var jiraMajorVersionFormat: String? = null,

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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    // --- Bidirectional collections (children of this configuration row;
    //     populated when overridden_attribute is a marker, or part of base) ---

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var vcsEntries: MutableList<VcsSettingsEntryEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var mavenArtifacts: MutableList<DistributionMavenArtifactEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var fileUrlArtifacts: MutableList<DistributionFileUrlArtifactEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var dockerImages: MutableList<DistributionDockerImageEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var packages: MutableList<DistributionPackageEntity> = mutableListOf(),

    @OneToMany(mappedBy = "componentConfiguration", fetch = FetchType.LAZY)
    var requiredToolJunctions: MutableList<ComponentRequiredToolEntity> = mutableListOf(),
)
