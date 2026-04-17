package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpdateRequest
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("rest/api/4/components")
@Suppress("TooManyFunctions")
class ComponentControllerV4(
    private val componentManagementService: ComponentManagementService,
    private val componentRepository: ComponentRepository,
) {
    @GetMapping("/meta/owners")
    fun getDistinctOwners(): List<String> = componentRepository.findDistinctOwners()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createComponent(
        @RequestBody request: ComponentCreateRequest,
    ): ComponentDetailResponse = componentManagementService.createComponent(request)

    @GetMapping
    fun listComponents(
        @RequestParam(required = false) system: String?,
        @RequestParam(required = false) productType: String?,
        @RequestParam(required = false) archived: Boolean?,
        @RequestParam(required = false) search: String?,
        pageable: Pageable,
    ): Page<ComponentSummaryResponse> {
        val filter =
            ComponentFilter(
                system = system,
                productType = productType,
                archived = archived,
                search = search,
            )
        return componentManagementService.listComponents(filter, pageable)
    }

    @GetMapping("/{idOrName}")
    fun getComponent(
        @PathVariable idOrName: String,
    ): ComponentDetailResponse {
        val asUuid = runCatching { UUID.fromString(idOrName) }.getOrNull()
        return if (asUuid != null) {
            componentManagementService.getComponent(asUuid)
        } else {
            componentManagementService.getComponentByName(idOrName)
        }
    }

    @PatchMapping("/{id}")
    fun updateComponent(
        @PathVariable id: UUID,
        @RequestBody request: ComponentUpdateRequest,
    ): ComponentDetailResponse = componentManagementService.updateComponent(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComponent(
        @PathVariable id: UUID,
    ) {
        componentManagementService.deleteComponent(id)
    }

    @PostMapping("/{id}/field-overrides")
    @ResponseStatus(HttpStatus.CREATED)
    fun createFieldOverride(
        @PathVariable id: UUID,
        @RequestBody request: FieldOverrideCreateRequest,
    ): FieldOverrideResponse = componentManagementService.createFieldOverride(id, request)

    @PatchMapping("/{id}/field-overrides/{overrideId}")
    fun updateFieldOverride(
        @PathVariable id: UUID,
        @PathVariable overrideId: UUID,
        @RequestBody request: FieldOverrideUpdateRequest,
    ): FieldOverrideResponse = componentManagementService.updateFieldOverride(id, overrideId, request)

    @DeleteMapping("/{id}/field-overrides/{overrideId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteFieldOverride(
        @PathVariable id: UUID,
        @PathVariable overrideId: UUID,
    ) {
        componentManagementService.deleteFieldOverride(id, overrideId)
    }

    @GetMapping("/{id}/field-overrides")
    fun listFieldOverrides(
        @PathVariable id: UUID,
    ): List<FieldOverrideResponse> = componentManagementService.listFieldOverrides(id)
}
