package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentTeamcityValidationRow
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityValidationSummaryResponse
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Read side of stored TeamCity validation findings, component-centric — the admin dashboard for
 * "which components fail validation / how many violate each check". Mirrors [ServiceEventControllerV4]
 * (IMPORT_DATA-gated read API). The trigger side lives on [AdminControllerV4]
 * (`POST /admin/teamcity-validation`); results are read here (`/admin/teamcity-validations`).
 */
// @PreAuthorize("@permissionEvaluator.canImport()")
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/admin/teamcity-validations")
class TeamcityValidationControllerV4(
    private val teamcityValidationQueryService: TeamcityValidationQueryService,
) {
    /** Component-centric findings; optional filters: validation `type`, `status`, `component` (name substring). */
    @GetMapping
    fun list(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) component: String?,
    ): List<ComponentTeamcityValidationRow> = teamcityValidationQueryService.list(type, status, component)

    /** Dashboard aggregates: components with issues, and distinct-component counts per type/status. */
    @GetMapping("/summary")
    fun summary(): TeamcityValidationSummaryResponse = teamcityValidationQueryService.summary()
}
