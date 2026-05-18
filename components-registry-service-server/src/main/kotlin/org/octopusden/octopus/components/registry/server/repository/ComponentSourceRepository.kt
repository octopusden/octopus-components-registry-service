package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface ComponentSourceRepository : JpaRepository<ComponentSourceEntity, String> {
    fun findBySource(source: String): List<ComponentSourceEntity>

    fun countBySource(source: String): Long

    /**
     * Atomic PK rewrite via bulk UPDATE: one statement, no delete/insert two-step.
     * Returns the number of rows updated (0 for a git-sourced component that was
     * never migrated, which is the correct no-op for the rename endpoint).
     */
    @Modifying
    @Transactional
    @Query("UPDATE ComponentSourceEntity c SET c.componentKey = :newKey WHERE c.componentKey = :oldKey")
    fun renameComponentKey(
        @Param("oldKey") oldKey: String,
        @Param("newKey") newKey: String,
    ): Int
}
