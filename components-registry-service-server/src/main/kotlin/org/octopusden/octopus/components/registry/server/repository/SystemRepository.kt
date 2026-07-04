package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.SystemEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface SystemRepository : JpaRepository<SystemEntity, String> {
    fun findByCode(code: String): SystemEntity?

    /**
     * All master-table system codes, sorted ascending. Backs
     * `GET /meta/systems/dictionary` — the "full dictionary" variant of
     * `/meta/systems`, which is sourced from the `component_systems`
     * junction (in-use only) via
     * `ComponentSystemRepository.findDistinctSystemCodes`.
     *
     * Defensively filters null and blank/whitespace-only codes, mirroring
     * the secondary-defence read filter on
     * `ComponentSystemRepository.findDistinctSystemCodes`. Even though
     * `systems.code` is a `NOT NULL` PK, schema-migration drift or a
     * direct DB write could still land a whitespace-only row, which would
     * surface as an unselectable blank chip in the Portal picker.
     */
    @Query(
        "SELECT s.code FROM SystemEntity s " +
            "WHERE s.code IS NOT NULL AND TRIM(s.code) <> '' " +
            "ORDER BY s.code",
    )
    fun findAllCodesSorted(): List<String>
}
