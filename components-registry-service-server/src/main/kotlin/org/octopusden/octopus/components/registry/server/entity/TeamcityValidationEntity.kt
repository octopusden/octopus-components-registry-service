package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/** Composite key: one row per (projectId, type) — the stable identity (no surrogate id). */
data class TeamcityValidationId(
    var projectId: String = "",
    var type: String = "",
) : Serializable

/** A stored finding (WARNING/ERROR only), latest-only per project; `updatedAt` = last written. */
@Entity
@Table(name = "teamcity_validation")
@IdClass(TeamcityValidationId::class)
class TeamcityValidationEntity(
    @Id
    @Column(name = "project_id", nullable = false)
    var projectId: String = "",
    @Id
    @Column(name = "type", length = 64, nullable = false)
    var type: String = "",
    @Column(name = "status", length = 32, nullable = false)
    var status: String = "",
    @Column(name = "message")
    var message: String? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
