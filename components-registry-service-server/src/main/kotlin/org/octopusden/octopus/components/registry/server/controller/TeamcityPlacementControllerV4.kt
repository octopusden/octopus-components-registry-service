package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationConflictResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityPlacementDiffJobResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityPlacementSyncJobResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityPlacementSyncRequest
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementReportRenderer
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ONB-002: TeamCity -> CRS VCS-placement Diff (read-only) and Sync (writes through the same v4
 * service methods a human PATCH uses). Split out of [AdminControllerV4] because, unlike every
 * other admin job, its REPORT is readable by anyone who can view components (design brief) while
 * both "run" endpoints stay behind `IMPORT_DATA` — a single class-level `@PreAuthorize` cannot
 * express that split.
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/admin/teamcity-placement")
class TeamcityPlacementControllerV4(
    private val diffJobService: TeamcityPlacementDiffJobService,
    private val syncJobService: TeamcityPlacementSyncJobService,
    private val currentUserResolver: CurrentUserResolver,
) {
    /** 202 on a freshly-started run, or (same-kind attach) the running job's state with 409; a
     * cross-kind conflict is mapped by [AdminControllerV4.handleCrossKindConflict]. */
    @PostMapping("/diff")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun startDiff(): ResponseEntity<TeamcityPlacementDiffJobResponse> {
        val outcome = diffJobService.startAsync(currentUserResolver.currentUsername())
        val httpStatus = if (outcome.isNewlyStarted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(httpStatus).body(TeamcityPlacementDiffJobResponse.from(outcome.state))
    }

    /** Latest known Diff job, or 404 if none has run since the pod came up. Read-only: same gate as the report. */
    @GetMapping("/diff/job")
    @PreAuthorize("@permissionEvaluator.canViewComponents()")
    fun getDiffJob(): ResponseEntity<TeamcityPlacementDiffJobResponse> {
        val state = diffJobService.current() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TeamcityPlacementDiffJobResponse.from(state))
    }

    /** The latest completed Diff's rows, for the Portal table. 404 until a Diff has completed at least once. */
    @GetMapping("/diff/report.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("@permissionEvaluator.canViewComponents()")
    fun getReportJson(): ResponseEntity<PlacementDiffResult> =
        latestReport()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/diff/report.html", produces = [MediaType.TEXT_HTML_VALUE])
    @PreAuthorize("@permissionEvaluator.canViewComponents()")
    fun getReportHtml(): ResponseEntity<String> =
        latestReport()?.let { ResponseEntity.ok(PlacementReportRenderer.toHtml(it)) } ?: ResponseEntity.notFound().build()

    @GetMapping("/diff/report.csv")
    @PreAuthorize("@permissionEvaluator.canViewComponents()")
    fun getReportCsv(): ResponseEntity<String> {
        val report = latestReport() ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=teamcity-placement-diff.csv")
            .body(PlacementReportRenderer.toCsv(report))
    }

    private fun latestReport(): PlacementDiffResult? = diffJobService.lastCompleted()?.result

    /**
     * Applies the last COMPLETED Diff's resolved rows for [request]'s component ids. 202 on a
     * freshly-started run; the 409 shapes are described on the `@ApiResponses` below.
     */
    @PostMapping("/sync")
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "A new Sync job was started"),
        ApiResponse(
            responseCode = "409",
            description = "Three body shapes, checked in this order. (1) A Sync is already running: the in-flight " +
                "TeamcityPlacementSyncJobResponse (kind \"job\"). (2) Another admin job holds the gate, a running Diff " +
                "included: MigrationConflictResponse {code, message, activeKind, activeJobId}. (3) diffId is not the last " +
                "completed Diff, or none has completed: ErrorResponse {errorMessage: \"diff replaced, re-run Diff\", " +
                "errorCode: \"placement-diff-stale\"}, nothing written; Diff keeps only that one result (ADR-002 decision 1).",
            content = [
                Content(
                    schema = Schema(
                        oneOf = [
                            MigrationConflictResponse::class,
                            TeamcityPlacementSyncJobResponse::class,
                            ErrorResponse::class,
                        ],
                    ),
                ),
            ],
        ),
    )
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun startSync(
        @RequestBody request: TeamcityPlacementSyncRequest,
    ): ResponseEntity<TeamcityPlacementSyncJobResponse> {
        // latestReport is called under the gate: a Diff completing before the claim makes this Sync stale.
        val outcome =
            syncJobService.startAsync(currentUserResolver.currentUsername(), request.componentIds, request.diffId, ::latestReport)
        val httpStatus = if (outcome.isNewlyStarted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(httpStatus).body(TeamcityPlacementSyncJobResponse.from(outcome.state))
    }

    /** Latest known Sync job, or 404 if none has run since the pod came up. */
    @GetMapping("/sync/job")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun getSyncJob(): ResponseEntity<TeamcityPlacementSyncJobResponse> {
        val state = syncJobService.current() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TeamcityPlacementSyncJobResponse.from(state))
    }

    /** The latest Sync run's rollback trace (ADR-002 decision 5): one row
     * per field it wrote, before and after. 404 until a Sync has completed at least once. Same
     * `IMPORT_DATA` gate as the rest of Sync -- this is what Sync wrote, not a public report. */
    @GetMapping("/sync/report.csv")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun getSyncReportCsv(): ResponseEntity<String> {
        val result = syncJobService.current()?.result ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=teamcity-placement-sync.csv")
            .body(PlacementReportRenderer.toSyncReportCsv(result))
    }
}
