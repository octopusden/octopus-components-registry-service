package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.Operation
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewResponse
import org.octopusden.octopus.components.registry.server.service.DistributionPreviewService
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * SYS-099: distribution preview for a component over a candidate override set.
 *
 * A read: it requires `ACCESS_COMPONENTS` like getComponent, never edit rights. Being a POST it
 * falls to the authenticated `rest/api/4` catch-all in WebSecurityConfig, so an anonymous caller
 * gets 401 (unlike the permitAll component GETs).
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/components")
class DistributionPreviewControllerV4(
    private val distributionPreviewService: DistributionPreviewService,
) {
    @Operation(
        summary = "Preview which source each distribution path takes for a version",
        description = "Resolves, for a concrete version, whether each distribution.* marker path takes BASE, " +
            "OVERRIDE or CONFLICT over the candidate overrides and supported versions in the body " +
            "(an omitted field = the saved state). Nothing is persisted.",
    )
    @PostMapping(
        "/{idOrName}/distribution/preview",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun preview(
        @PathVariable idOrName: String,
        @RequestBody request: DistributionPreviewRequest,
    ): DistributionPreviewResponse = distributionPreviewService.preview(idOrName, request)
}
