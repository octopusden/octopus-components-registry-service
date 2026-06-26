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
import org.hibernate.annotations.BatchSize
import java.util.UUID

/**
 * One artifact-ownership mapping of a component (replaces `ComponentArtifactIdEntity`
 * and the per-range `group-artifact-pattern` marker as ownership carrier).
 *
 * A component owns a LIST of these. Each mapping declares a comma-separated
 * [groupPattern] (literal group tokens) + an ownership [artifactIdMode]
 * (`EXPLICIT` | `ALL_EXCEPT_CLAIMED` | `ALL`) for a [versionRange]:
 *  - base mapping → `versionRange = ALL_VERSIONS`;
 *  - per-range override → an existing component-configuration range (the override
 *    REPLACES the base mapping for that range — most-specific range wins).
 *
 * [sortOrder] preserves declaration order; `sortOrder = 0` is the PRIMARY mapping
 * rendered into the legacy v1–v3 single `(groupIdPattern, artifactIdPattern)` pair.
 * Literal tokens (EXPLICIT only) live in [tokens]; ALL / ALL_EXCEPT_CLAIMED carry no
 * tokens — their catch-all behaviour is derived from the mode at render/resolve time.
 */
@Entity
@Table(name = "component_artifact_mappings")
class ComponentArtifactMappingEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    var component: ComponentEntity,

    @Column(name = "version_range", nullable = false)
    var versionRange: String = "",

    @Column(name = "group_pattern", columnDefinition = "TEXT", nullable = false)
    var groupPattern: String = "",

    @Column(name = "artifact_id_mode", length = 20, nullable = false)
    var artifactIdMode: String = ArtifactIdMode.ALL.name,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @OneToMany(mappedBy = "mapping", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = BATCH_FETCH_SIZE)
    var tokens: MutableList<ComponentArtifactMappingTokenEntity> = mutableListOf(),
)

/** Ownership mode for a [ComponentArtifactMappingEntity]; persisted as the enum NAME. */
enum class ArtifactIdMode {
    /** Owns exactly the literal [ComponentArtifactMappingTokenEntity] tokens. Highest specificity. */
    EXPLICIT,

    /** Owns any artifact under the group NOT explicitly claimed by another component (catch-all that yields). */
    ALL_EXCEPT_CLAIMED,

    /** Owns every artifact under the group, unconditionally (sole owner of the group in its range). */
    ALL,
}
