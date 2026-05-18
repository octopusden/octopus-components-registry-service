package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentBuildToolBeanRepository : JpaRepository<ComponentBuildToolBeanEntity, UUID> {
    fun findByComponentConfigurationId(componentConfigurationId: UUID): List<ComponentBuildToolBeanEntity>
}
