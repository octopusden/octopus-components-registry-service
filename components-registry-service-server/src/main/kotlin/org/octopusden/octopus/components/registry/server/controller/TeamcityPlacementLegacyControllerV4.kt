package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationConflictResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityCheckoutLayoutDiffJobResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityCheckoutLayoutSyncJobResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityCheckoutLayoutSyncRequest
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutDiffResult
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private const val DEPRECATED = "Deprecated: use the same path under /rest/api/4/admin/teamcity-checkout-layout"

/**
 * DEPRECATED (2026-10): the first name of [TeamcityCheckoutLayoutControllerV4]'s endpoints, kept for
 * path compatibility. Every call delegates to it: same permissions and bodies, with the renamed error and
 * gate codes. Removed once the Portal uses the new paths in every environment.
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/admin/teamcity-placement")
class TeamcityPlacementLegacyControllerV4(
    private val current: TeamcityCheckoutLayoutControllerV4,
) {
    @Deprecated(DEPRECATED)
    @Operation(deprecated = true, summary = DEPRECATED, operationId = "startDiffLegacy")
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "A new Diff job was started"),
        ApiResponse(
            responseCode = "409",
            description = DIFF_CONFLICT_DESCRIPTION,
            content = [
                Content(
                    schema = Schema(oneOf = [MigrationConflictResponse::class, TeamcityCheckoutLayoutDiffJobResponse::class]),
                ),
            ],
        ),
    )
    @PostMapping("/diff")
    fun startDiff(): ResponseEntity<TeamcityCheckoutLayoutDiffJobResponse> = current.startDiff()

    @Deprecated(DEPRECATED)
    @Operation(deprecated = true, summary = DEPRECATED, operationId = "getDiffJobLegacy")
    @GetMapping("/diff/job")
    fun getDiffJob(): ResponseEntity<TeamcityCheckoutLayoutDiffJobResponse> = current.getDiffJob()

    @Deprecated(DEPRECATED)
    @Operation(deprecated = true, summary = DEPRECATED, operationId = "getReportJsonLegacy")
    @GetMapping("/diff/report.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getReportJson(): ResponseEntity<CheckoutLayoutDiffResult> = current.getReportJson()

    @Deprecated(DEPRECATED)
    @Operation(deprecated = true, summary = DEPRECATED, operationId = "getReportHtmlLegacy")
    @GetMapping("/diff/report.html", produces = [MediaType.TEXT_HTML_VALUE])
    fun getReportHtml(): ResponseEntity<String> = current.getReportHtml()

    @Deprecated(DEPRECATED)
    @Operation(deprecated = true, summary = DEPRECATED, operationId = "getReportCsvLegacy")
    @GetMapping("/diff/report.csv")
    fun getReportCsv(): ResponseEntity<String> = current.getReportCsv()

    @Deprecated(DEPRECATED)
    @Operation(deprecated = true, summary = DEPRECATED, operationId = "startSyncLegacy")
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "A new Sync job was started"),
        ApiResponse(
            responseCode = "409",
            description = SYNC_CONFLICT_DESCRIPTION,
            content = [
                Content(
                    schema = Schema(
                        oneOf = [
                            MigrationConflictResponse::class,
                            TeamcityCheckoutLayoutSyncJobResponse::class,
                            ErrorResponse::class,
                        ],
                    ),
                ),
            ],
        ),
    )
    @PostMapping("/sync")
    fun startSync(
        @RequestBody request: TeamcityCheckoutLayoutSyncRequest,
    ): ResponseEntity<TeamcityCheckoutLayoutSyncJobResponse> = current.startSync(request)

    @Deprecated(DEPRECATED)
    @Operation(deprecated = true, summary = DEPRECATED, operationId = "getSyncJobLegacy")
    @GetMapping("/sync/job")
    fun getSyncJob(): ResponseEntity<TeamcityCheckoutLayoutSyncJobResponse> = current.getSyncJob()

    @Deprecated(DEPRECATED)
    @Operation(deprecated = true, summary = DEPRECATED, operationId = "getSyncReportCsvLegacy")
    @GetMapping("/sync/report.csv")
    fun getSyncReportCsv(): ResponseEntity<String> = current.getSyncReportCsv()
}
