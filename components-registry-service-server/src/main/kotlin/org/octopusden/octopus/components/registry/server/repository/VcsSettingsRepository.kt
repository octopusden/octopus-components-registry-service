package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface VcsSettingsRepository : JpaRepository<VcsSettingsEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<VcsSettingsEntity>

    fun findByComponentVersionId(componentVersionId: UUID): List<VcsSettingsEntity>
}
