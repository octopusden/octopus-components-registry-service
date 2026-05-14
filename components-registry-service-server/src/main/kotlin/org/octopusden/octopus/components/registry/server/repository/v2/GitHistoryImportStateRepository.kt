package org.octopusden.octopus.components.registry.server.repository.v2

import org.octopusden.octopus.components.registry.server.entity.v2.GitHistoryImportStateEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface GitHistoryImportStateRepository : JpaRepository<GitHistoryImportStateEntity, String>
