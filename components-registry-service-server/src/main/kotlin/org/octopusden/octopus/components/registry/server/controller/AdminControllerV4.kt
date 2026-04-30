package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.dto.v4.HistoryMigrationJobResponse
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationConflictResponse
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationJobResponse
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStatus
import org.octopusden.octopus.components.registry.server.repository.GitHistoryImportStateRepository
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.HistoryMigrationJobService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationConflictException
import org.octopusden.octopus.components.registry.server.service.MigrationJobService
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationResult
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.octopusden.octopus.components.registry.server.service.ValidationResult
import org.octopusden.octopus.components.registry.server.service.impl.GitHistoryCommitWriter
import org.slf4j.LoggerFactory
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
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("rest/api/4/admin")
@PreAuthorize("@permissionEvaluator.canImport()")
@Suppress("TooManyFunctions") // Each migration / import endpoint is its own method; consistent with ComponentControllerV4.
class AdminControllerV4(
    private val importService: ImportService,
    private val migrationJobService: MigrationJobService,
    private val historyMigrationJobService: HistoryMigrationJobService,
    private val historyCommitWriter: GitHistoryCommitWriter,
    private val historyStateRepository: GitHistoryImportStateRepository,
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
     * rows with source='git-history'. Used to recover from stale IN_PROGRESS
     * claims left by a prior pod restart, or to bootstrap a re-import on a
     * stuck COMPLETED row when reset=true is also blocked.
     *
     * Two layers of guard against multi-pod corruption (P1 review fix):
     *
     * 1. **In-pod gate** — refused with 409 if a history migration is
     *    currently RUNNING in *this* pod. Defense-in-depth against a curl
     *    call racing the Run flow.
     * 2. **Cross-pod staleness gate** — refused with 409 if the DB row is
     *    IN_PROGRESS with a fresh `updatedAt` (< STALE_IN_PROGRESS_THRESHOLD).
     *    A live import in *another* pod would have a recent updatedAt; this
     *    catches the cross-pod overlap that the in-pod gate cannot. Operators
     *    who explicitly need to interrupt a fresh live import can override
     *    with `?ack-multipod-risk=true` — that's an audit-loud opt-in, not a
     *    silent default.
     *
     * Always logs the destructive action before performing it so operators
     * have an audit trail even when the DB row gets wiped. Idempotent on
     * an empty DB (returns 204 even when nothing was deleted).
     */
    @PostMapping("/migrate-history/force-reset")
    fun forceResetHistory(
        @RequestParam(name = "ack-multipod-risk", defaultValue = "false") ackMultipodRisk: Boolean,
    ): ResponseEntity<Any> {
        // Guard 1: same-pod active job.
        val active = historyMigrationJobService.current()
        if (active?.state == JobState.RUNNING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                MigrationConflictResponse(
                    code = "history-migration-running",
                    message =
                        "Cannot force-reset while history migration is RUNNING (jobId=${active.id}). " +
                            "Wait for it to finish or restart the pod.",
                    activeKind = MigrationLifecycleGate.JobKind.HISTORY.name,
                    activeJobId = active.id,
                ),
            )
        }

        // Guard 2: cross-pod staleness. Inspect the DB row directly (NOT via
        // current() — that one synthesizes a JobState rather than exposing
        // updatedAt).
        val row = historyStateRepository.findById(IMPORT_KEY).orElse(null)
        if (row != null &&
            row.status == GitHistoryImportStatus.IN_PROGRESS.name &&
            !ackMultipodRisk
        ) {
            val age = Duration.between(row.updatedAt, Instant.now())
            if (age < STALE_IN_PROGRESS_THRESHOLD) {
                // INFO not WARN: this is the *successful* defense path —
                // the gate worked, no operator needs to be paged. The WARN
                // is reserved for the override case below where a live
                // import in another pod might be about to lose data.
                LOG.info(
                    "Refusing force-reset: IN_PROGRESS row updated {} ago (threshold={}). " +
                        "Likely a live import in another pod. Override with ack-multipod-risk=true if you accept the data-corruption risk.",
                    age,
                    STALE_IN_PROGRESS_THRESHOLD,
                )
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    MigrationConflictResponse(
                        code = "history-import-likely-live-elsewhere",
                        message =
                            "Refusing force-reset: the IN_PROGRESS claim was updated ${age.toSeconds()}s ago, " +
                                "below the ${STALE_IN_PROGRESS_THRESHOLD.toMinutes()}-minute staleness threshold. " +
                                "A live import in another pod is likely. " +
                                "If you understand the multi-pod risk and want to proceed anyway, " +
                                "POST again with ?ack-multipod-risk=true.",
                        activeKind = MigrationLifecycleGate.JobKind.HISTORY.name,
                        // Owned by another pod — we can't read its in-memory job id from here.
                        activeJobId = null,
                    ),
                )
            }
        }

        // Audit-loud destructive action: log before the wipe so the pre-state
        // survives the operation in the log stream. ack-multipod-risk=true is
        // logged at WARN to make the override visible in alert pipelines.
        if (row != null) {
            val msg =
                "FORCE-RESET: deleting git_history_import_state " +
                    "(target=${row.targetRef}@${row.targetSha}, status=${row.status}, " +
                    "updatedAt=${row.updatedAt}) and all audit_log rows with source='git-history'."
            // ack-multipod-risk overrides the staleness check, which means the
            // operator just told us "I accept potential data corruption."
            // ERROR level so alert pipelines that filter on ERROR-only catch
            // this — same reasoning as FaultInjectionConfig's startup banner.
            if (ackMultipodRisk) LOG.error("$msg [ack-multipod-risk=true overriding staleness check]") else LOG.info(msg)
        } else {
            LOG.info("FORCE-RESET: no state row to delete (idempotent no-op on empty DB)")
        }

        historyCommitWriter.forceReset()
        historyMigrationJobService.clearInMemory()
        return ResponseEntity.noContent().build()
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

    companion object {
        private val LOG = LoggerFactory.getLogger(AdminControllerV4::class.java)

        /**
         * Threshold for considering an IN_PROGRESS DB row "stale enough that no
         * one in any pod is actively writing to it." Conservative: a real
         * import keeps `updated_at` fresh via the per-50-commits heartbeat
         * call from `GitHistoryImportServiceImpl.runImport`. If we haven't
         * seen a write in 30 minutes, the original pod is almost certainly
         * gone.
         *
         * Operators who genuinely need to interrupt a fresh live import can
         * bypass with `?ack-multipod-risk=true` — explicit acknowledgement
         * that data corruption is acceptable.
         */
        private val STALE_IN_PROGRESS_THRESHOLD: Duration = Duration.ofMinutes(30)
        private const val IMPORT_KEY = "component-history"
    }
}
