package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.FieldOverrideEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FieldOverrideRepository : JpaRepository<FieldOverrideEntity, UUID> {
    fun findByComponentId(componentId: UUID): List<FieldOverrideEntity>

    fun findByComponentIdAndFieldPath(
        componentId: UUID,
        fieldPath: String,
    ): List<FieldOverrideEntity>

    @Query("SELECT f FROM FieldOverrideEntity f WHERE f.component.name = :componentName")
    fun findByComponentName(
        @Param("componentName") componentName: String,
    ): List<FieldOverrideEntity>
}
