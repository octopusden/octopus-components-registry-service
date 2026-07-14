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
 * Ordered multi-value security-champion username for a component. Identical
 * shape to [ComponentReleaseManagerEntity] (surrogate UUID PK, `sort_order`
 * for the ordered list). See that class's KDoc for the rationale behind the
 * surrogate key and service-layer dedupe.
 */
@Entity
@Table(name = "component_security_champions")
class ComponentSecurityChampionEntity(
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
