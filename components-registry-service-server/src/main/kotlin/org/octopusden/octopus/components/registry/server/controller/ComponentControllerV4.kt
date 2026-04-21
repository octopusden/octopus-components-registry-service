package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
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
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
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

/**
 * `@PreAuthorize` is applied method-by-method rather than at class level because
 * Spring Security 6's method-level annotation **replaces** a class-level one instead
 * of AND-ing with it. A class-level `ACCESS_COMPONENTS` + method-level
 * `EDIT_COMPONENTS` would silently let a user with only `EDIT_COMPONENTS` bypass the
 * read gate, which is the opposite of what "class-level default" suggests. Every
 * endpoint now declares the full set of permissions it requires.
 */
@RestController
@RequestMapping("rest/api/4/components")
@Suppress("TooManyFunctions")
class ComponentControllerV4(
    private val componentManagementService: ComponentManagementService,
    private val componentRepository: ComponentRepository,
) {
    private val log = LoggerFactory.getLogger(ComponentControllerV4::class.java)

    @GetMapping("/meta/owners")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getDistinctOwners(): List<String> = componentRepository.findDistinctOwners()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.hasPermission('EDIT_COMPONENTS')",
    )
    fun createComponent(
        @RequestBody request: ComponentCreateRequest,
    ): ComponentDetailResponse = componentManagementService.createComponent(request)

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
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
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getComponent(
        @PathVariable idOrName: String,
    ): ComponentDetailResponse {
        // Prefer UUID lookup when the path parses as one, but fall through to the
        // name lookup ONLY for NotFoundException — the sentinel for "no row with
        // this id". Infra / server-side errors (DB down, NPE, etc.) must surface
        // to the caller instead of silently being re-routed to a name lookup
        // that would mask the real failure. Ensures a component whose `name`
        // happens to parse as a UUID still resolves by name.
        val asUuid = runCatching { UUID.fromString(idOrName) }.getOrNull()
        if (asUuid != null) {
            try {
                return componentManagementService.getComponent(asUuid)
            } catch (e: NotFoundException) {
                // Id not found — continue to the name lookup below. Log at debug
                // so unrelated callers aren't noisy in production; the name
                // lookup will either succeed or raise its own NotFoundException
                // which the handler maps to the 404 the caller sees.
                log.debug("id lookup missed for '{}', falling back to name lookup: {}", idOrName, e.message)
            }
        }
        return componentManagementService.getComponentByName(idOrName)
    }

    @PatchMapping("/{id}")
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.canEditComponent(#id.toString())",
    )
    fun updateComponent(
        @PathVariable id: UUID,
        @RequestBody request: ComponentUpdateRequest,
    ): ComponentDetailResponse = componentManagementService.updateComponent(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.canDeleteComponent(#id.toString())",
    )
    fun deleteComponent(
        @PathVariable id: UUID,
    ) {
        componentManagementService.deleteComponent(id)
    }

    @PostMapping("/{id}/field-overrides")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.hasPermission('EDIT_COMPONENTS')",
    )
    fun createFieldOverride(
        @PathVariable id: UUID,
        @RequestBody request: FieldOverrideCreateRequest,
    ): FieldOverrideResponse = componentManagementService.createFieldOverride(id, request)

    @PatchMapping("/{id}/field-overrides/{overrideId}")
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.hasPermission('EDIT_COMPONENTS')",
    )
    fun updateFieldOverride(
        @PathVariable id: UUID,
        @PathVariable overrideId: UUID,
        @RequestBody request: FieldOverrideUpdateRequest,
    ): FieldOverrideResponse = componentManagementService.updateFieldOverride(id, overrideId, request)

    @DeleteMapping("/{id}/field-overrides/{overrideId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.hasPermission('EDIT_COMPONENTS')",
    )
    fun deleteFieldOverride(
        @PathVariable id: UUID,
        @PathVariable overrideId: UUID,
    ) {
        componentManagementService.deleteFieldOverride(id, overrideId)
    }

    @GetMapping("/{id}/field-overrides")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun listFieldOverrides(
        @PathVariable id: UUID,
    ): List<FieldOverrideResponse> = componentManagementService.listFieldOverrides(id)
}
