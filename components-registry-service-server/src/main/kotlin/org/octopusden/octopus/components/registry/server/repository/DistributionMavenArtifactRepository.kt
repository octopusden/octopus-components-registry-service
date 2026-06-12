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
            "m.extension AS extension, m.classifier AS classifier, " +
            "cfg.versionRange AS versionRange, cfg.overriddenAttribute AS overriddenAttribute, " +
            "comp.componentKey AS componentKey " +
            "FROM DistributionMavenArtifactEntity m " +
            "JOIN m.componentConfiguration cfg " +
            "JOIN cfg.component comp " +
            "WHERE comp.id <> :excludeComponentId",
    )
    fun findOtherComponents(
        @Param("excludeComponentId") excludeComponentId: UUID,
    ): List<CrossComponentMavenRow>

    /**
     * Every maven-artifact row in the DB with its owning component key. Used by
     * the migration uniqueness pre-pass to check incoming DSL rows against the
     * already-persisted state (the migrated-or-API-born components a new import
     * must not collide with).
     */
    @Query(
        "SELECT m.groupPattern AS groupPattern, m.artifactPattern AS artifactPattern, " +
            "m.extension AS extension, m.classifier AS classifier, " +
            "cfg.versionRange AS versionRange, cfg.overriddenAttribute AS overriddenAttribute, " +
            "comp.componentKey AS componentKey " +
            "FROM DistributionMavenArtifactEntity m " +
            "JOIN m.componentConfiguration cfg " +
            "JOIN cfg.component comp",
    )
    fun findAllRows(): List<CrossComponentMavenRow>
}

/**
 * Projection for cross-component maven-artifact collision detection. The full
 * artifact identity is `(groupPattern, artifactPattern, extension, classifier)`
 * — see `MavenGavCollision`. `versionRange` is the owning configuration row's
 * range; `componentKey` identifies the rival component for the conflict message.
 */
interface CrossComponentMavenRow {
    val groupPattern: String
    val artifactPattern: String
    val extension: String?
    val classifier: String?
    val versionRange: String

    /**
     * Owning row's marker attribute — `group-artifact-pattern` rows are synthesized
     * from the component-level `groupId`/`artifactId` mapping, NOT from a
     * `distribution { GAV }` block; conflict messages must name the real source
     * (`MavenGavCollision.originLabel`).
     */
    val overriddenAttribute: String?
    val componentKey: String
}
