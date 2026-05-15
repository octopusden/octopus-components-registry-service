package org.octopusden.octopus.components.registry.server.entity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "component_groups")
class ComponentGroupEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(name = "group_key", nullable = false, unique = true)
    var groupKey: String = "",

    @Column(name = "is_fake", nullable = false)
    var isFake: Boolean = false,

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)
