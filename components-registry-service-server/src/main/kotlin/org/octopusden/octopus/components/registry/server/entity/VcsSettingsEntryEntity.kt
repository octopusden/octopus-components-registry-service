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
 * Unified VCS model: all VCS entries carry a non-null `name`.
 * Inline DSL form (`vcsUrl=` / `branch=` at module level) yields `name = "main"`;
 * named-block form (`vcsSettings { key { ... } }`) yields `name = "<key>"`.
 * MULTI-VCS is N rows with distinct `name` values. No discriminator column.
 * `repository_type` carries the VCS engine (`GIT` / `MERCURIAL` / `CVS`);
 * typically `GIT`.
 * `source_path` / `checkout_directory` place the entry on the build agent (ONB-001): the primary
 * entry (`sort_order` 0) has no checkout directory; a secondary's `name` equals its checkout directory.
 */
@Entity
@Table(name = "vcs_settings_entries")
class VcsSettingsEntryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_configuration_id", nullable = false)
    var componentConfiguration: ComponentConfigurationEntity,
    @Column(name = "name", nullable = false)
    var name: String,
    @Column(name = "vcs_path", columnDefinition = "TEXT", nullable = false)
    var vcsPath: String = "",
    @Column(name = "branch", columnDefinition = "TEXT")
    var branch: String? = null,
    @Column(name = "tag", columnDefinition = "TEXT")
    var tag: String? = null,
    @Column(name = "hotfix_branch", columnDefinition = "TEXT")
    var hotfixBranch: String? = null,
    @Column(name = "repository_type", length = 20)
    var repositoryType: String? = null,
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
    @Column(name = "source_path")
    var sourcePath: String? = null,
    @Column(name = "checkout_directory")
    var checkoutDirectory: String? = null,
)
