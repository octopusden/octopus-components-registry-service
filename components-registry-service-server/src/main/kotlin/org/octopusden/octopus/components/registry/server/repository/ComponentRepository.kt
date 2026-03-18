package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ComponentRepository :
    JpaRepository<ComponentEntity, UUID>,
    JpaSpecificationExecutor<ComponentEntity> {
    fun findByName(name: String): ComponentEntity?

    fun findByArchivedFalse(): List<ComponentEntity>

    fun findByArchivedFalse(pageable: Pageable): Page<ComponentEntity>

    @Query("SELECT c FROM ComponentEntity c LEFT JOIN FETCH c.versions WHERE c.name = :name")
    fun findByNameWithVersions(
        @Param("name") name: String,
    ): ComponentEntity?

    @Query(
        "SELECT c FROM ComponentEntity c LEFT JOIN FETCH c.versions " +
            "LEFT JOIN FETCH c.buildConfigurations LEFT JOIN FETCH c.escrowConfigurations " +
            "LEFT JOIN FETCH c.vcsSettings LEFT JOIN FETCH c.distributions " +
            "LEFT JOIN FETCH c.jiraComponentConfigs LEFT JOIN FETCH c.artifactIds " +
            "WHERE c.name = :name",
    )
    fun findByNameWithAllRelations(
        @Param("name") name: String,
    ): ComponentEntity?

    @Query(
        "SELECT c FROM ComponentEntity c LEFT JOIN FETCH c.versions " +
            "LEFT JOIN FETCH c.buildConfigurations LEFT JOIN FETCH c.escrowConfigurations " +
            "LEFT JOIN FETCH c.vcsSettings LEFT JOIN FETCH c.distributions " +
            "LEFT JOIN FETCH c.jiraComponentConfigs LEFT JOIN FETCH c.artifactIds " +
            "WHERE c.id = :id",
    )
    fun findByIdWithAllRelations(
        @Param("id") id: UUID,
    ): ComponentEntity?

    fun existsByName(name: String): Boolean

    @Query("SELECT DISTINCT c.componentOwner FROM ComponentEntity c WHERE c.componentOwner IS NOT NULL ORDER BY c.componentOwner")
    fun findDistinctOwners(): List<String>
}
