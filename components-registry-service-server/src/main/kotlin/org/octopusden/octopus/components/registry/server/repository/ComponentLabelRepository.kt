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
     */
    @Query("SELECT DISTINCT cl.labelCode FROM ComponentLabelEntity cl ORDER BY cl.labelCode")
    fun findDistinctLabelCodes(): List<String>
}
