package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.HistoryMigrationJobResponse
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationConflictResponse
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationJobResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcitySyncJobResponse
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityValidationJobResponse
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
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
import org.octopusden.octopus.components.registry.server.service.impl.ConfigValidationException
import org.octopusden.octopus.components.registry.server.teamcity.sync.TeamcitySyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationJobService
import org.springframework.cloud.context.refresh.ContextRefresher
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

@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/admin")
@PreAuthorize("@permissionEvaluator.canImport()")
@Suppress("TooManyFunctions") // Each migration / import endpoint is its own method; consistent with ComponentControllerV4.
class AdminControllerV4(
    private val importService: ImportService,
    private val migrationJobService: MigrationJobService,
    private val historyMigrationJobService: HistoryMigrationJobService,
    private val teamcitySyncJobService: TeamcitySyncJobService,
    private val teamcityValidationJobService: TeamcityValidationJobService,
    private val contextRefresher: ContextRefresher,
    private val currentUserResolver: CurrentUserResolver,
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
        // Forbid re-running once the migration is complete: `git` is the count of DSL
        // (git-sourced) components not yet in the DB, so git==0 means there is nothing left
        // to migrate. Re-running would only re-do defaults and confuse operators. A partial
        // state (git>0, e.g. after failures) still allows a retry. Fail-loud (409) so a direct
        // API caller is blocked too, not only the SPA button.
        if (importService.getMigrationStatus().git == 0L) {
            throw MigrationAlreadyCompleteException(
                "Migration already complete: no git-sourced components remain (git=0). Nothing to migrate.",
            )
        }
        val outcome = migrationJobService.startAsync(currentUserResolver.currentUsername())
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

    /**
     * Reload the code-as-config admin blobs (`field-config` + `component-defaults`)
     * from `service-config` WITHOUT a pod restart.
     *
     * Triggers `ContextRefresher.refresh()`, which re-fetches the Spring Cloud Config
     * profile, rebinds [AdminConfigProperties][org.octopusden.octopus.components.registry.server.config.AdminConfigProperties]
     * (via `EnvironmentChangeEvent`) and publishes `RefreshScopeRefreshedEvent` — the
     * latter drives
     * [ConfigRefreshListener][org.octopusden.octopus.components.registry.server.listener.ConfigRefreshListener]
     * to re-sync both blobs into the `registry_config` cache synchronously. Returns the
     * set of property keys that changed.
     */
    @PostMapping("/reload-config")
    fun reloadConfig(): ResponseEntity<Map<String, Any?>> {
        val changed = contextRefresher.refresh()
        return ResponseEntity.ok(mapOf("status" to "reloaded", "changedKeys" to changed.sorted()))
    }

    /**
     * A bad service-config value surfaces from `reload-config` (via the refresh →
     * sync path) as a [ConfigValidationException]. Map it to 422 with the actionable
     * message instead of an opaque 500; the DB cache is left untouched (no-clobber).
     */
    @ExceptionHandler(ConfigValidationException::class)
    fun handleConfigValidation(e: ConfigValidationException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            mapOf("error" to "config-validation", "message" to (e.message ?: "Invalid configuration")),
        )

    /**
     * Re-running a completed migration (git==0) is a 409 with a distinct `code` so the SPA
     * can tell it apart from the same-kind attach 409 (which carries a job body).
     */
    @ExceptionHandler(MigrationAlreadyCompleteException::class)
    fun handleMigrationAlreadyComplete(e: MigrationAlreadyCompleteException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("code" to "migration-complete", "message" to (e.message ?: "Migration already complete")),
        )

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
        val outcome = historyMigrationJobService.startAsync(toRef, reset, currentUserResolver.currentUsername())
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
     * Async TC resync — kicks off the run on the background executor.
     *
     * Returns 202 Accepted with a [TeamcitySyncJobResponse] describing the
     * freshly-started job. If a TC resync is already RUNNING in this pod,
     * returns 409 Conflict with the existing job's state — the SPA "attaches"
     * to the in-flight job rather than spawning a duplicate. Either way,
     * callers poll `GET /admin/teamcity-project-ids/sync/job` for progress
     * and pick up the final per-pass counts once `state == COMPLETED`.
     *
     * If the OTHER migration kind (components / history) is currently RUNNING,
     * the service throws [MigrationConflictException] which the handler below
     * maps to a 409 with [MigrationConflictResponse] body so the SPA can
     * surface a clear "something else is running" message.
     */
    @PostMapping("/teamcity-project-ids/sync")
    fun startTeamcitySync(): ResponseEntity<TeamcitySyncJobResponse> {
        val outcome = teamcitySyncJobService.startAsync(currentUserResolver.currentUsername())
        val httpStatus = if (outcome.isNewlyStarted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(httpStatus).body(TeamcitySyncJobResponse.from(outcome.state))
    }

    /**
     * Returns the latest known [TeamcitySyncJobResponse], or 404 if no TC
     * resync has been started since the pod came up.
     */
    @GetMapping("/teamcity-project-ids/sync/job")
    fun getTeamcitySyncJob(): ResponseEntity<TeamcitySyncJobResponse> {
        val state = teamcitySyncJobService.current() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TeamcitySyncJobResponse.from(state))
    }

    /**
     * Kick off a TeamCity Java/Maven validation run on the background executor.
     * 202 with the freshly-started job, or (same-kind attach) the running job's
     * state with 409; a cross-kind conflict is mapped to a structured 409 by
     * [handleCrossKindConflict].
     */
    @PostMapping("/teamcity-validation")
    fun startTeamcityValidation(): ResponseEntity<TeamcityValidationJobResponse> {
        val outcome = teamcityValidationJobService.startAsync(currentUserResolver.currentUsername())
        val httpStatus = if (outcome.isNewlyStarted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(httpStatus).body(TeamcityValidationJobResponse.from(outcome.state))
    }

    /** Latest known TeamCity validation job state, or 404 if none. */
    @GetMapping("/teamcity-validation/job")
    fun getTeamcityValidationJob(): ResponseEntity<TeamcityValidationJobResponse> {
        val state = teamcityValidationJobService.current() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TeamcityValidationJobResponse.from(state))
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
                MigrationLifecycleGate.JobKind.TC_RESYNC -> "tc-resync-running"
                MigrationLifecycleGate.JobKind.TC_VALIDATION -> "tc-validation-running"
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

/**
 * Thrown by `POST /admin/migrate` when the migration is already complete (no git-sourced
 * components remain). Mapped to 409 `{code: "migration-complete"}` by the controller so a
 * finished migration cannot be re-run from the SPA or a direct API call.
 */
class MigrationAlreadyCompleteException(
    message: String,
) : RuntimeException(message)
