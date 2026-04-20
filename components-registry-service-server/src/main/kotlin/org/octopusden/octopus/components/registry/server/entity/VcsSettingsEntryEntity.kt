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

@Entity
@Table(name = "vcs_settings_entries")
class VcsSettingsEntryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vcs_settings_id", nullable = false)
    var vcsSettings: VcsSettingsEntity? = null,
    var name: String? = null,
    // see TD-002
    @Column(name = "vcs_path", nullable = false, columnDefinition = "TEXT")
    var vcsPath: String = "",
    @Column(name = "repository_type", nullable = false)
    var repositoryType: String = "GIT",
    @Column(columnDefinition = "TEXT")
    var tag: String? = null,
    @Column(columnDefinition = "TEXT")
    var branch: String? = null,
    @Column(name = "hotfix_branch", columnDefinition = "TEXT")
    var hotfixBranch: String? = null,
)
