package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ComponentSourceRepository : JpaRepository<ComponentSourceEntity, String> {
    fun findBySource(source: String): List<ComponentSourceEntity>

    fun countBySource(source: String): Long

    /**
     * Rewrite the primary key of a `component_source` row in a single statement.
     * Replaces the earlier `delete → flush → save` pattern so the rename is one
     * SQL statement against the DB — trivially atomic, no intermediate two-row
     * window even under exotic isolation levels, and no need for a mid-tx flush.
     */
    @Modifying
    @Query(
        "UPDATE ComponentSourceEntity cs SET cs.componentName = :newName " +
            "WHERE cs.componentName = :oldName",
    )
    fun renameComponentName(
        @Param("oldName") oldName: String,
        @Param("newName") newName: String,
    ): Int
}
