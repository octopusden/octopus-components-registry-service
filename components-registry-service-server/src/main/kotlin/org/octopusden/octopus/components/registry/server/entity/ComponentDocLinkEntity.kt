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
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/**
 * Doc-link records reference a documentation component by its key string —
 * a soft reference. The target may be archived or out of installation, so no
 * `@ManyToOne` to `ComponentEntity` by `doc_component_key`.
 *
 * The partial UNIQUE index `uq_doc_links_null_major_version` is enforced
 * at the DB layer (V1__schema.sql); JPA only needs to model the table-level
 * UNIQUE on (component_id, doc_component_key, major_version).
 */
@Entity
@Table(name = "component_doc_links")
class ComponentDocLinkEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    var component: ComponentEntity,
    @Column(name = "doc_component_key", nullable = false)
    var docComponentKey: String = "",
    @Column(name = "major_version", length = 50)
    var majorVersion: String? = null,
    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)
