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
 * One literal artifact token of an EXPLICIT [ComponentArtifactMappingEntity]
 * (`component_artifact_mapping_tokens`). Tokens are stored UNESCAPED (literal
 * artifact IDs, allowlist `[A-Za-z0-9_.-]+`); regex metacharacters are escaped only
 * when rendering to the legacy v1–v3 wire / DSL. ALL and ALL_EXCEPT_CLAIMED mappings
 * carry NO token rows. [sortOrder] preserves declaration order.
 */
@Entity
@Table(name = "component_artifact_mapping_tokens")
class ComponentArtifactMappingTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false)
    var mapping: ComponentArtifactMappingEntity,

    @Column(name = "artifact_pattern", columnDefinition = "TEXT", nullable = false)
    var artifactPattern: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)
