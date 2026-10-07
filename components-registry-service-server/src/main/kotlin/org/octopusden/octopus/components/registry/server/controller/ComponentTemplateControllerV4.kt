package org.octopusden.octopus.components.registry.server.controller

import jakarta.validation.Valid
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentTemplateResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TemplateComponentRequest
import org.octopusden.octopus.components.registry.server.dto.v4.TemplateRunResponse
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.security.PermissionEvaluator
import org.octopusden.octopus.components.registry.server.template.ComponentTemplate
import org.octopusden.octopus.components.registry.server.template.ListValues
import org.octopusden.octopus.components.registry.server.template.TemplateDryRun
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Templates for a creator: describe one, and dry-run or create a component from it. Database mode
 * only, like the create. The template is looked up at the moment of each call, so one a reload
 * removed or failed answers 404.
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/component-templates")
class ComponentTemplateControllerV4(
    private val catalog: ComponentProfileCatalog,
    private val availability: ProfileAvailability,
    private val lists: ListValues,
    private val dryRun: TemplateDryRun,
    private val currentUser: CurrentUserResolver,
    private val permissionEvaluator: PermissionEvaluator,
) {
    @GetMapping("/{id}")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun describe(
        @PathVariable id: String,
    ): ComponentTemplateResponse = ComponentTemplateResponse.from(live(id), lists, currentUser.currentUsername())

    /** `dryRun` defaults to `true`, so a caller that leaves it out never creates a component. */
    @PostMapping("/{id}/components")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun components(
        @PathVariable id: String,
        @RequestParam(defaultValue = "true") dryRun: Boolean,
        @Valid @RequestBody request: TemplateComponentRequest,
    ): ResponseEntity<Any> {
        val template = live(id)
        val usable = availability.evaluate(template)
        if (!usable.usable) throw ResponseStatusException(HttpStatus.FORBIDDEN, usable.reason)
        if (request.overrides.isNotEmpty() && !availability.mayOverride(template)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "overriding fields of template '$id' is not allowed for this user")
        }
        val run = this.dryRun.run(template, request.toInput(), currentUser.currentUsername(), commit = !dryRun)
        val created = run.created
        return when {
            dryRun -> ResponseEntity.ok(TemplateRunResponse.from(run))
            created != null ->
                ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(created.copy(canEdit = permissionEvaluator.canEditComponent(created.id.toString())))
            else -> ResponseEntity.unprocessableEntity().body(TemplateRunResponse.from(run))
        }
    }

    private fun live(id: String): ComponentTemplate = catalog.template(id) ?: throw NotFoundException("Component template '$id' not found")
}
