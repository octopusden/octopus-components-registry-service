package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.Operation
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.RMSSweepStatusResponse
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildParametersService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin visibility into the RMS build-parameters sweep itself — last run, how long it took,
 * current error, and which components RMS is unreachable for. Mirrors
 * [TeamcityValidationControllerV4] (IMPORT_DATA-gated read API over a background/cached dataset).
 */
@PreAuthorize("@permissionEvaluator.canImport()")
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/admin/rms-sweep")
class RMSSweepControllerV4(
    private val rmsBuildParametersService: RMSBuildParametersService,
) {
    @GetMapping
    @Operation(operationId = "getRmsSweepStatus")
    fun status(): RMSSweepStatusResponse =
        RMSSweepStatusResponse.from(rmsBuildParametersService.isEnabled(), rmsBuildParametersService.currentReport())
}
