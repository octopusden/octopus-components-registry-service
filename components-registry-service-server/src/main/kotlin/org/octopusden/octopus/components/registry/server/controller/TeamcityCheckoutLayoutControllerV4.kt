package org.octopusden.octopus.components.registry.server.controller

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
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutReportRenderer
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncJobService
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

/** The 409 shapes of `POST /diff`, shared with the deprecated alias. */
internal const val DIFF_CONFLICT_DESCRIPTION =
    "Two body shapes. (1) A Diff is already running: the in-flight TeamcityCheckoutLayoutDiffJobResponse. " +
        "(2) Another admin job holds the gate: MigrationConflictResponse {code, message, activeKind, activeJobId}."

/** The 409 shapes of `POST /sync`, shared with the deprecated alias. */
internal const val SYNC_CONFLICT_DESCRIPTION =
    "Three body shapes, checked in this order. (1) A Sync is already running: the in-flight " +
        "TeamcityCheckoutLayoutSyncJobResponse (kind \"job\"). (2) Another admin job holds the gate, a running Diff " +
        "included: MigrationConflictResponse {code, message, activeKind, activeJobId}. (3) diffId is not the last " +
        "completed Diff, or none has completed: ErrorResponse {errorMessage: \"diff replaced, re-run Diff\", " +
        "errorCode: \"checkout-layout-diff-stale\"}, nothing written; Diff keeps only that one result (ADR-023 decision 1)."

/**
 * ONB-002: TeamCity -> CRS VCS checkout-layout Diff (read-only) and Sync (writes through the same v4
 * service methods a human PATCH uses). Split out of [AdminControllerV4] because, unlike every
 * other admin job, its REPORT is readable by anyone who can view components while
 * both "run" endpoints stay behind `IMPORT_DATA` — a single class-level `@PreAuthorize` cannot
 * express that split.
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/admin/teamcity-checkout-layout")
class TeamcityCheckoutLayoutControllerV4(
    private val diffJobService: TeamcityCheckoutLayoutDiffJobService,
    private val syncJobService: TeamcityCheckoutLayoutSyncJobService,
    private val currentUserResolver: CurrentUserResolver,
) {
    /** 202 on a freshly-started run, or (same-kind attach) the running job's state with 409; a
     * cross-kind conflict is mapped by [ControllerExceptionHandler.migrationConflictExceptionHandler]. */
    @PostMapping("/diff")
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
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun startDiff(): ResponseEntity<TeamcityCheckoutLayoutDiffJobResponse> {
        val outcome = diffJobService.startAsync(currentUserResolver.currentUsername())
        val httpStatus = if (outcome.isNewlyStarted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(httpStatus).body(TeamcityCheckoutLayoutDiffJobResponse.from(outcome.state))
    }

    /** Latest known Diff job, or 404 if none has run since the pod came up. Read-only: same gate as the report. */
    @GetMapping("/diff/job")
    @PreAuthorize("@permissionEvaluator.canViewComponents()")
    fun getDiffJob(): ResponseEntity<TeamcityCheckoutLayoutDiffJobResponse> {
        val state = diffJobService.current() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TeamcityCheckoutLayoutDiffJobResponse.from(state))
    }

    /** The latest completed Diff's rows, for the Portal table. 404 until a Diff has completed at least once. */
    @GetMapping("/diff/report.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("@permissionEvaluator.canViewComponents()")
    fun getReportJson(): ResponseEntity<CheckoutLayoutDiffResult> =
        latestReport()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/diff/report.html", produces = [MediaType.TEXT_HTML_VALUE])
    @PreAuthorize("@permissionEvaluator.canViewComponents()")
    fun getReportHtml(): ResponseEntity<String> =
        latestReport()?.let { ResponseEntity.ok(CheckoutLayoutReportRenderer.toHtml(it)) } ?: ResponseEntity.notFound().build()

    @GetMapping("/diff/report.csv")
    @PreAuthorize("@permissionEvaluator.canViewComponents()")
    fun getReportCsv(): ResponseEntity<String> {
        val report = latestReport() ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=teamcity-checkout-layout-diff.csv")
            .body(CheckoutLayoutReportRenderer.toCsv(report))
    }

    private fun latestReport(): CheckoutLayoutDiffResult? = diffJobService.lastCompleted()?.result

    /**
     * Applies the last COMPLETED Diff's resolved rows for [request]'s component ids. 202 on a
     * freshly-started run; the 409 shapes are described on the `@ApiResponses` below.
     */
    @PostMapping("/sync")
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
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun startSync(
        @RequestBody request: TeamcityCheckoutLayoutSyncRequest,
    ): ResponseEntity<TeamcityCheckoutLayoutSyncJobResponse> {
        // latestReport is called under the gate: a Diff completing before the claim makes this Sync stale.
        val outcome =
            syncJobService.startAsync(currentUserResolver.currentUsername(), request.componentIds, request.diffId, ::latestReport)
        val httpStatus = if (outcome.isNewlyStarted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(httpStatus).body(TeamcityCheckoutLayoutSyncJobResponse.from(outcome.state))
    }

    /** Latest known Sync job, or 404 if none has run since the pod came up. */
    @GetMapping("/sync/job")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun getSyncJob(): ResponseEntity<TeamcityCheckoutLayoutSyncJobResponse> {
        val state = syncJobService.current() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TeamcityCheckoutLayoutSyncJobResponse.from(state))
    }

    /** The latest Sync run's rollback trace (ADR-023 decision 5): one row
     * per field it wrote, before and after. 404 until a Sync has completed at least once. Same
     * `IMPORT_DATA` gate as the rest of Sync -- this is what Sync wrote, not a public report. */
    @GetMapping("/sync/report.csv")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun getSyncReportCsv(): ResponseEntity<String> {
        val result = syncJobService.current()?.result ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=teamcity-checkout-layout-sync.csv")
            .body(CheckoutLayoutReportRenderer.toSyncReportCsv(result))
    }
}
