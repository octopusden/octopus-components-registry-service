package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DistributionMavenArtifactRepository : JpaRepository<DistributionMavenArtifactEntity, UUID> {
    fun findByComponentConfigurationId(componentConfigurationId: UUID): List<DistributionMavenArtifactEntity>

    /**
     * Maven-artifact rows of OTHER components (excluding [excludeComponentId]).
     * Candidate narrowing cannot use exact SQL equality because legacy artifact
     * patterns support wildcard, regex/CSV artifacts, and CSV group IDs. Pattern
     * overlap and Maven range intersection are therefore decided in-memory.
     */
    @Query(
        "SELECT m.groupPattern AS groupPattern, m.artifactPattern AS artifactPattern, " +
            "cfg.versionRange AS versionRange, comp.componentKey AS componentKey " +
            "FROM DistributionMavenArtifactEntity m " +
            "JOIN m.componentConfiguration cfg " +
            "JOIN cfg.component comp " +
            "WHERE comp.id <> :excludeComponentId",
    )
    fun findOtherComponents(
        @Param("excludeComponentId") excludeComponentId: UUID,
    ): List<CrossComponentMavenRow>
}

/**
 * Projection for cross-component maven-artifact collision detection. `versionRange`
 * is the owning configuration row's range; `componentKey` identifies the rival
 * component for the conflict message.
 */
interface CrossComponentMavenRow {
    val groupPattern: String
    val artifactPattern: String
    val versionRange: String
    val componentKey: String
}
