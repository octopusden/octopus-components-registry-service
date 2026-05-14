package org.octopusden.octopus.components.registry.server.entity.v2

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(name = "tools")
class ToolEntity(
    @Id
    @Column(name = "name", length = 100, nullable = false)
    var name: String = "",

    @Column(name = "escrow_env_variable")
    var escrowEnvVariable: String? = null,

    @Column(name = "target_location", columnDefinition = "TEXT")
    var targetLocation: String? = null,

    @Column(name = "source_location", columnDefinition = "TEXT")
    var sourceLocation: String? = null,

    @Column(name = "install_script", columnDefinition = "TEXT")
    var installScript: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)
