package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.LabelEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface LabelRepository : JpaRepository<LabelEntity, String> {
    fun findByCode(code: String): LabelEntity?

    /**
     * All master-table label codes, sorted ascending. Backs
     * `GET /meta/labels/dictionary` — the "full dictionary" variant of
     * `/meta/labels`, which is junction-sourced (in-use only).
     *
     * Defensively filters null and blank/whitespace-only codes, mirroring
     * the secondary-defence read filter on `ComponentLabelRepository
     * .findDistinctLabelCodes`: even though the master `labels.code` is
     * `NOT NULL` PK, schema-migration drift or direct DB writes could
     * still land a whitespace-only row, which would surface as an
     * unselectable blank chip in the Portal picker.
     */
    @Query(
        "SELECT l.code FROM LabelEntity l " +
            "WHERE l.code IS NOT NULL AND TRIM(l.code) <> '' " +
            "ORDER BY l.code",
    )
    fun findAllCodesSorted(): List<String>
}
