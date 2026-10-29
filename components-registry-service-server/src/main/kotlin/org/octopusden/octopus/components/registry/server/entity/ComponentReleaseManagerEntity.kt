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
 * Ordered multi-value release-manager username for a component. Parent-owned
 * child row mirroring `ComponentArtifactIdEntity` — a surrogate `@GeneratedValue`
 * UUID `@Id` (NOT an `@IdClass` composite on `sort_order`, which would trigger
 * Hibernate INSERT-before-DELETE PK collisions when the clear/re-add edit
 * pattern re-numbers slots). `sortOrder` preserves the ordered list (first =
 * primary). Username uniqueness within a component is enforced by the
 * keep-first dedupe in `ComponentEntity.replaceReleaseManagerUsernames`, not a
 * DB constraint.
 */
@Entity
@Table(name = "component_release_managers")
class ComponentReleaseManagerEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    var component: ComponentEntity,
    @Column(name = "username", nullable = false)
    var username: String = "",
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)
