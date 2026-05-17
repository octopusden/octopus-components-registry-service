package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ToolEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ToolRepository : JpaRepository<ToolEntity, String> {
    fun findByName(name: String): ToolEntity?
}
