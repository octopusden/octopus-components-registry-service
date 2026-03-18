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
import java.util.UUID

@Entity
@Table(name = "vcs_settings")
class VcsSettingsEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id")
    var component: ComponentEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_version_id")
    var componentVersion: ComponentVersionEntity? = null,
    @Column(name = "vcs_type", nullable = false)
    var vcsType: String = "SINGLE",
    // see TD-002
    @Column(name = "external_registry", columnDefinition = "TEXT")
    var externalRegistry: String? = null,
    @OneToMany(mappedBy = "vcsSettings", cascade = [CascadeType.ALL], orphanRemoval = true)
    var entries: MutableList<VcsSettingsEntryEntity> = mutableListOf(),
)
