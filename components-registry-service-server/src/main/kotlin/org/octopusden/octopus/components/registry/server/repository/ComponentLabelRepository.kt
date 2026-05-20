package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentLabelRepository : JpaRepository<ComponentLabelEntity, ComponentLabelId> {
    fun findByComponentId(componentId: UUID): List<ComponentLabelEntity>

    /**
     * Distinct label codes currently attached to at least one component.
     *
     * Sourced from the junction (not from the master LabelEntity table) so
     * the Portal picker advertises only labels actually in use — parity
     * with ComponentRepository.findDistinctOwners which sources from
     * components.componentOwner. A master label that no component carries
     * would create a dead option in the picker.
     *
     * Defensively filters null and blank/whitespace-only labelCodes —
     * mirrors the IS NOT NULL + non-empty guard on findDistinctOwners.
     * The write path (controller + service) DOES canonicalise label codes
     * (trim + drop blank + dedupe) as of the SYS-040 write-side fix, so
     * blank rows should not appear from new writes. This clause remains
     * as defence-in-depth against schema-migration drift, direct DB
     * writes, or pre-fix legacy rows already in the junction at upgrade
     * time — without it, a stray "" or "   " row would surface as an
     * unselectable blank chip in the picker.
     */
    @Query(
        "SELECT DISTINCT cl.labelCode FROM ComponentLabelEntity cl " +
            "WHERE cl.labelCode IS NOT NULL AND TRIM(cl.labelCode) <> '' " +
            "ORDER BY cl.labelCode",
    )
    fun findDistinctLabelCodes(): List<String>
}
