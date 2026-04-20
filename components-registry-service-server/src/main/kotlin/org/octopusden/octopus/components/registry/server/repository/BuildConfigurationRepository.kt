package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.BuildConfigurationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BuildConfigurationRepository : JpaRepository<BuildConfigurationEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<BuildConfigurationEntity>

    fun findByComponentVersionId(componentVersionId: UUID): List<BuildConfigurationEntity>
}
