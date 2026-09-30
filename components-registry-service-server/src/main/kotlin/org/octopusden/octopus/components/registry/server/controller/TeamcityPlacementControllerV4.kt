package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
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
import org.springframework.web.server.ResponseStatusException

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

    private fun latestReport(): PlacementDiffResult? = diffJobService.current()?.result

    /**
     * Applies the latest Diff's resolved rows for [request]'s component ids ("select all
     * resolved" from the Portal table). Refused with 409 up front, before any component is
     * considered and before the job is even submitted, when [request]'s `diffId` no longer
     * matches the latest Diff (ADR-002 decision 1) -- Diff keeps no history, so a Sync always
     * acts on the result the user actually looked at, never on one that has since been replaced.
     * Otherwise: 202 on a freshly-started run, 409 (same-kind attach or cross-kind) exactly like
     * every other admin job.
     */
    @PostMapping("/sync")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun startSync(
        @RequestBody request: TeamcityPlacementSyncRequest,
    ): ResponseEntity<TeamcityPlacementSyncJobResponse> {
        val currentDiffId = diffJobService.current()?.id
        if (request.diffId != currentDiffId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "diff replaced, re-run Diff")
        }
        val outcome = syncJobService.startAsync(currentUserResolver.currentUsername(), request.componentIds)
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
}
