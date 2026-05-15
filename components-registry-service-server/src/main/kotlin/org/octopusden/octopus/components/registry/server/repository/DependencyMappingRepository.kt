package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.DependencyMappingEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DependencyMappingRepository : JpaRepository<DependencyMappingEntity, String>
