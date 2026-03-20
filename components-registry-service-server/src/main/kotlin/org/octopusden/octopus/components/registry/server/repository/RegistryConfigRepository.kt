package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.springframework.data.jpa.repository.JpaRepository

interface RegistryConfigRepository : JpaRepository<RegistryConfigEntity, String>
