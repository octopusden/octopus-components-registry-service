package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentSystemEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentSystemId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentSystemRepository : JpaRepository<ComponentSystemEntity, ComponentSystemId> {
    fun findByComponentId(componentId: UUID): List<ComponentSystemEntity>

    /**
     * Distinct system codes currently attached to at least one component.
     *
     * Sourced from the component_systems junction (not from the master
     * SystemEntity / systems table) so the Portal picker advertises only
     * systems actually in use — parity with /meta/owners (sourced from
     * components.componentOwner) and /meta/labels (sourced from the
     * component_labels junction). A master system that no component
     * carries would create a dead option in the picker.
     *
     * Defensively filters null and blank/whitespace-only systemCodes —
     * mirrors the IS NOT NULL + non-empty guard on findDistinctOwners and
     * findDistinctLabelCodes. A stray "" or "   " in the junction (from
     * schema-migration drift or a direct DB write) would otherwise
     * surface as an unselectable blank chip in the picker.
     */
    @Query(
        "SELECT DISTINCT cs.systemCode FROM ComponentSystemEntity cs " +
            "WHERE cs.systemCode IS NOT NULL AND TRIM(cs.systemCode) <> '' " +
            "ORDER BY cs.systemCode",
    )
    fun findDistinctSystemCodes(): List<String>
}
