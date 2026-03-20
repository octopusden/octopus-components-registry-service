package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ComponentSourceRepository : JpaRepository<ComponentSourceEntity, String> {
    fun findBySource(source: String): List<ComponentSourceEntity>

    fun countBySource(source: String): Long
}
