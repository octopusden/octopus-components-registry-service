package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentArtifactMappingRepository : JpaRepository<ComponentArtifactMappingEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<ComponentArtifactMappingEntity>

    /**
     * Artifact-ownership mappings of OTHER components (excluding [excludeComponentId]),
     * projected for the cross-component uniqueness check. Tokens are loaded separately
     * (EXPLICIT only); `mode`/`groupPattern`/`versionRange` drive the mode-aware matrix.
     * Candidate narrowing cannot use SQL equality (CSV groups, literal-token sets), so
     * the matrix is decided in-memory.
     */
    @Query(
        "SELECT m.groupPattern AS groupPattern, m.artifactIdMode AS artifactIdMode, " +
            "m.versionRange AS versionRange, comp.componentKey AS componentKey, m.id AS mappingId " +
            "FROM ComponentArtifactMappingEntity m " +
            "JOIN m.component comp " +
            "WHERE comp.id <> :excludeComponentId",
    )
    fun findOtherComponents(
        @Param("excludeComponentId") excludeComponentId: UUID,
    ): List<CrossComponentMappingRow>

    /** Every ownership mapping in the DB with its owning component key — migration pre-pass DB side. */
    @Query(
        "SELECT m.groupPattern AS groupPattern, m.artifactIdMode AS artifactIdMode, " +
            "m.versionRange AS versionRange, comp.componentKey AS componentKey, m.id AS mappingId " +
            "FROM ComponentArtifactMappingEntity m " +
            "JOIN m.component comp",
    )
    fun findAllRows(): List<CrossComponentMappingRow>
}

/**
 * Projection for cross-component ownership-uniqueness. The EXPLICIT tokens of a row are
 * fetched on demand by [mappingId] (most rows are catch-all and carry no tokens).
 */
interface CrossComponentMappingRow {
    val componentKey: String
    val mappingId: UUID
    val groupPattern: String
    val artifactIdMode: String
    val versionRange: String
}
