package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.dto.v4.HistoryMigrationJobResponse
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationConflictResponse
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationJobResponse
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.ForceResetOutcome
import org.octopusden.octopus.components.registry.server.service.HistoryMigrationJobService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.MigrationConflictException
import org.octopusden.octopus.components.registry.server.service.MigrationJobService
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationResult
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.octopusden.octopus.components.registry.server.service.ValidationResult
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/4/admin")
@PreAuthorize("@permissionEvaluator.canImport()")
@Suppress("TooManyFunctions") // Each migration / import endpoint is its own method; consistent with ComponentControllerV4.
class AdminControllerV4(
    private val importService: ImportService,
    private val migrationJobService: MigrationJobService,
    private val historyMigrationJobService: HistoryMigrationJobService,
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
     * result once `state == COMPLETED`.
     *
     * If the OTHER migration kind (history) is currently RUNNING, the service
     * throws [MigrationConflictException] which the handler below maps to a
     * 409 with [MigrationConflictResponse] body.
     */
    @PostMapping("/migrate")
    fun migrate(): ResponseEntity<MigrationJobResponse> {
        val outcome = migrationJobService.startAsync()
        val httpStatus = if (outcome.isNewlyStarted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(httpStatus).body(MigrationJobResponse.from(outcome.state))
    }

    /**
     * Returns the latest known [MigrationJobResponse], or 404 if no migration has been started
     * since the pod came up.
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

    /**
     * Async git-history backfill. Mirrors POST /migrate for components:
     * 202 on a freshly-started job, 409 with an attach-able job body on a
     * same-kind retry (poll /migrate-history/job for live state).
     *
     * `toRef` defaults to the auto-resolved tag matching the configured prefix;
     * `reset=true` is required to re-run on top of an existing terminal
     * git_history_import_state row.
     */
    @PostMapping("/migrate-history")
    fun migrateHistory(
        @RequestParam(required = false) toRef: String?,
        @RequestParam(defaultValue = "false") reset: Boolean,
    ): ResponseEntity<HistoryMigrationJobResponse> {
        val outcome = historyMigrationJobService.startAsync(toRef, reset)
        val httpStatus = if (outcome.isNewlyStarted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(httpStatus).body(HistoryMigrationJobResponse.from(outcome.state))
    }

    /**
     * Returns the latest known history-migration job — including a state
     * synthesized from `git_history_import_state` if no in-memory job is
     * active in this pod (so the SPA sees prior-pod outcomes after restart
     * and surfaces the right action — see HistoryMigrationJobServiceImpl A7.1).
     * 404 only when there is neither in-memory state nor a DB row.
     */
    @GetMapping("/migrate-history/job")
    fun getHistoryMigrateJob(): ResponseEntity<HistoryMigrationJobResponse> {
        val state = historyMigrationJobService.current() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(HistoryMigrationJobResponse.from(state))
    }

    /**
     * Destructive: deletes the git_history_import_state row AND all audit_log
     * rows with source='git-history'. Guards and audit logging are handled by
     * [HistoryMigrationJobService.forceReset]; this method maps the outcome to
     * HTTP. Returns 204 on success (idempotent — same response when the DB was
     * already empty), 409 with a [MigrationConflictResponse] body when any guard
     * blocks the wipe.
     */
    @PostMapping("/migrate-history/force-reset")
    fun forceResetHistory(
        @RequestParam(name = "ack-multipod-risk", defaultValue = "false") ackMultipodRisk: Boolean,
    ): ResponseEntity<Any> =
        when (val outcome = historyMigrationJobService.forceReset(ackMultipodRisk)) {
            is ForceResetOutcome.Cleared -> ResponseEntity.noContent().build()
            is ForceResetOutcome.Blocked ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    MigrationConflictResponse(
                        code = outcome.code,
                        message = outcome.message,
                        activeKind = outcome.activeKind,
                        activeJobId = outcome.activeJobId,
                    ),
                )
        }

    /**
     * Map cross-kind gate conflicts to a structured 409. Same-kind 409 is
     * NOT routed here — it returns from startAsync as `isNewlyStarted=false`
     * with the existing job state body so the SPA can attach.
     */
    @ExceptionHandler(MigrationConflictException::class)
    fun handleCrossKindConflict(e: MigrationConflictException): ResponseEntity<MigrationConflictResponse> {
        val code =
            when (e.active.kind) {
                MigrationLifecycleGate.JobKind.COMPONENTS -> "components-migration-running"
                MigrationLifecycleGate.JobKind.HISTORY -> "history-migration-running"
            }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            MigrationConflictResponse(
                code = code,
                message = e.message ?: "Cross-kind migration conflict",
                activeKind = e.active.kind.name,
                activeJobId = e.active.jobId,
            ),
        )
    }
}
