package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DistributionDockerImageRepository : JpaRepository<DistributionDockerImageEntity, UUID> {
    fun findByComponentConfigurationId(componentConfigurationId: UUID): List<DistributionDockerImageEntity>

    /**
     * Distinct component keys (other than [excludeComponentId]) that already
     * declare a docker image with [imageName]. Docker image names are globally
     * unique across components (the old `validateDockerUniqueNames` rule); a
     * non-empty result is a cross-component conflict. Indexed equality on
     * `image_name`; DISTINCT so a rival declaring the same image in several
     * version ranges is reported once.
     */
    @Query(
        "SELECT DISTINCT comp.componentKey FROM DistributionDockerImageEntity d " +
            "JOIN d.componentConfiguration cfg " +
            "JOIN cfg.component comp " +
            "WHERE comp.id <> :excludeComponentId AND d.imageName = :imageName",
    )
    fun findOtherComponentKeysByImageName(
        @Param("imageName") imageName: String,
        @Param("excludeComponentId") excludeComponentId: UUID,
    ): List<String>
}
