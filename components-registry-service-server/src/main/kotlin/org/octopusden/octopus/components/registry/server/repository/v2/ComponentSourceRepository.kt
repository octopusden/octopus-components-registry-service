package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.ComponentSourceEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ComponentSourceRepository : JpaRepository<ComponentSourceEntity, String> {
    fun findBySource(source: String): List<ComponentSourceEntity>
}
