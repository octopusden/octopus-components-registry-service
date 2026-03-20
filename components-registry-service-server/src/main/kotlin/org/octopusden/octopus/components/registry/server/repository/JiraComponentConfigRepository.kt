package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.JiraComponentConfigEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface JiraComponentConfigRepository : JpaRepository<JiraComponentConfigEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<JiraComponentConfigEntity>

    fun findByComponentVersionId(componentVersionId: UUID): List<JiraComponentConfigEntity>

    fun findByProjectKey(projectKey: String): List<JiraComponentConfigEntity>
}
