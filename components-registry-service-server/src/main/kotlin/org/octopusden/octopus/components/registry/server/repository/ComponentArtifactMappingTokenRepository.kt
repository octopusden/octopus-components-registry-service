package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentArtifactMappingTokenRepository : JpaRepository<ComponentArtifactMappingTokenEntity, UUID> {
    /**
     * Literal tokens of the given EXPLICIT mappings, batch-loaded for the cross-component
     * uniqueness matrix (catch-all mappings have none). `(mappingId, artifactPattern)` ordered
     * by `sortOrder` so callers can group by mappingId.
     */
    @Query(
        "SELECT t.mapping.id AS mappingId, t.artifactPattern AS artifactPattern " +
            "FROM ComponentArtifactMappingTokenEntity t " +
            "WHERE t.mapping.id IN :mappingIds ORDER BY t.sortOrder ASC",
    )
    fun findTokensByMappingIdIn(
        @Param("mappingIds") mappingIds: Collection<UUID>,
    ): List<MappingTokenRow>
}

/** Projection: a literal artifact token belonging to an EXPLICIT mapping. */
interface MappingTokenRow {
    val mappingId: UUID
    val artifactPattern: String
}
