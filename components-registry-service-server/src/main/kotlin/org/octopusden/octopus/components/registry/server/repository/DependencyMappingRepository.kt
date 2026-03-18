package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.DependencyMappingEntity
import org.springframework.data.jpa.repository.JpaRepository

interface DependencyMappingRepository : JpaRepository<DependencyMappingEntity, String>
