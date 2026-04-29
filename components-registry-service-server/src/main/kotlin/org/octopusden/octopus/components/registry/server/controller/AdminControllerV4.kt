package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.dto.v4.HistoryImportResult
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationJobResponse
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.GitHistoryImportService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.MigrationJobService
import org.octopusden.octopus.components.registry.server.service.MigrationResult
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.octopusden.octopus.components.registry.server.service.ValidationResult
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/4/admin")
@PreAuthorize("@permissionEvaluator.canImport()")
class AdminControllerV4(
    private val importService: ImportService,
    private val gitHistoryImportService: GitHistoryImportService,
    private val migrationJobService: MigrationJobService,
) {
    @PostMapping("/migrate-component/{name}")
    fun migrateComponent(
        @PathVariable name: String,
        @RequestParam(defaultValue = "false") dryRun: Boolean,
    ): ResponseEntity<MigrationResult> = ResponseEntity.ok(importService.migrateComponent(name, dryRun))

    @PostMapping("/migrate-components")
    fun migrateAllComponents(): ResponseEntity<BatchMigrationResult> = ResponseEntity.ok(importService.migrateAllComponents())

    @GetMapping("/migration-status")
    fun getMigrationStatus(): ResponseEntity<MigrationStatus> = ResponseEntity.ok(importService.getMigrationStatus())

    @PostMapping("/validate-migration/{name}")
    fun validateMigration(
        @PathVariable name: String,
    ): ResponseEntity<ValidationResult> = ResponseEntity.ok(importService.validateMigration(name))

    @PostMapping("/import")
    fun importComponents(): ResponseEntity<BatchMigrationResult> = ResponseEntity.ok(importService.migrateAllComponents())

    /**
     * Kick off a full Git→DB migration on the background executor.
     *
     * Returns 202 Accepted with a [MigrationJobResponse] describing the freshly-
     * started job. If a migration is already RUNNING, returns 409 Conflict with
     * the existing job's state — the SPA "attaches" to the in-flight job rather
     * than spawning a duplicate. Either way, callers can poll
     * `GET /admin/migrate/job` to watch progress and pick up the final
     * [MigrationJobResponse.result] once `state == COMPLETED`.
     */
    @PostMapping("/migrate")
    fun migrate(): ResponseEntity<MigrationJobResponse> {
        val outcome = migrationJobService.startAsync()
        val httpStatus = if (outcome.isNewlyStarted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(httpStatus).body(MigrationJobResponse.from(outcome.state))
    }

    /**
     * Returns the latest known [MigrationJobResponse], or 404 if no migration has been started
     * since the pod came up. While the job is RUNNING, response carries `currentComponent`
     * and per-component counters that the SPA renders as a progress bar; once
     * `state == COMPLETED`, `result` is populated with the full [FullMigrationResult].
     */
    @GetMapping("/migrate/job")
    fun getMigrateJob(): ResponseEntity<MigrationJobResponse> {
        val state = migrationJobService.current() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(MigrationJobResponse.from(state))
    }

    @PostMapping("/migrate-defaults")
    fun migrateDefaults(): ResponseEntity<Map<String, Any?>> = ResponseEntity.ok(importService.migrateDefaults())

    @GetMapping("/export")
    fun exportComponents(): ResponseEntity<Map<String, String>> = ResponseEntity.ok(mapOf("status" to "not_implemented"))

    @PostMapping("/migrate-history")
    fun migrateHistory(
        @RequestParam(required = false) toRef: String?,
        @RequestParam(defaultValue = "false") reset: Boolean,
    ): ResponseEntity<HistoryImportResult> = ResponseEntity.ok(gitHistoryImportService.importHistory(toRef, reset))
}
