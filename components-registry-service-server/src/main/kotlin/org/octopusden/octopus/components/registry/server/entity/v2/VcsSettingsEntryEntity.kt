package org.octopusden.octopus.components.registry.server.entity.v2

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
 * Unified VCS model: SINGLE-VCS is a single row with `name = NULL`;
 * MULTI-VCS is N rows with distinct `name` values. No discriminator column.
 * `repository_type` carries the VCS engine (`GIT` / `MERCURIAL` / `CVS`);
 * typically `GIT`.
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

    @Column(name = "name")
    var name: String? = null,

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
)
