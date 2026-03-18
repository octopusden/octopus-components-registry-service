package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpdateRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface ComponentManagementService {
    fun createComponent(request: ComponentCreateRequest): ComponentDetailResponse

    fun getComponent(id: UUID): ComponentDetailResponse

    fun getComponentByName(name: String): ComponentDetailResponse

    fun updateComponent(
        id: UUID,
        request: ComponentUpdateRequest,
    ): ComponentDetailResponse

    fun deleteComponent(id: UUID)

    fun listComponents(
        filter: ComponentFilter,
        pageable: Pageable,
    ): Page<ComponentSummaryResponse>

    fun createFieldOverride(
        componentId: UUID,
        request: FieldOverrideCreateRequest,
    ): FieldOverrideResponse

    fun updateFieldOverride(
        componentId: UUID,
        overrideId: UUID,
        request: FieldOverrideUpdateRequest,
    ): FieldOverrideResponse

    fun deleteFieldOverride(
        componentId: UUID,
        overrideId: UUID,
    )

    fun listFieldOverrides(componentId: UUID): List<FieldOverrideResponse>
}
