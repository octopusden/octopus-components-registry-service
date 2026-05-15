package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface GitHistoryImportStateRepository : JpaRepository<GitHistoryImportStateEntity, String>
