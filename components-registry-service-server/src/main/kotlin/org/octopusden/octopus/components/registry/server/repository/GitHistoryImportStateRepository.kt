package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.springframework.data.jpa.repository.JpaRepository

interface GitHistoryImportStateRepository : JpaRepository<GitHistoryImportStateEntity, String>
