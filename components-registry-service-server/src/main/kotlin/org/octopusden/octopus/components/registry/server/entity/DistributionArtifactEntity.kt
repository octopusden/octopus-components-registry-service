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
@Table(name = "distribution_artifacts")
class DistributionArtifactEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distribution_id", nullable = false)
    var distribution: DistributionEntity? = null,
    @Column(name = "artifact_type", nullable = false)
    var artifactType: String = "",
    // see TD-002
    @Column(name = "group_pattern", columnDefinition = "TEXT")
    var groupPattern: String? = null,
    @Column(name = "artifact_pattern", columnDefinition = "TEXT")
    var artifactPattern: String? = null,
    @Column(columnDefinition = "TEXT")
    var classifier: String? = null,
    var extension: String? = null,
    @Column(columnDefinition = "TEXT")
    var name: String? = null,
    @Column(columnDefinition = "TEXT")
    var tag: String? = null,
)
