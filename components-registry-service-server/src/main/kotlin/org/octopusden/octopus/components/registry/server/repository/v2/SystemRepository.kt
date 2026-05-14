package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.SystemEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SystemRepository : JpaRepository<SystemEntity, String> {
    fun findByCode(code: String): SystemEntity?
}
