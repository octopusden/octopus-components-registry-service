package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@Entity
@Table(name = "systems")
class SystemEntity(
    @Id
    @Column(name = "code", length = 50, nullable = false)
    var code: String = "",

    @Column(name = "name")
    var name: String? = null,

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)
