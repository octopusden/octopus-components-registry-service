package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.Operation
import java.util.concurrent.atomic.AtomicBoolean
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.server.service.ComponentsRegistryService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/2/components-registry/service")
class ComponentsRegistryServiceController(
    private val componentsRegistryService: ComponentsRegistryService,
    // Nullable: ImportService is part of the database layer (@ConditionalOnDatabaseEnabled)
    // and is therefore absent in no-db mode. Spring treats a Kotlin-nullable constructor
    // parameter as an optional dependency, injecting null when no bean exists.
    private val importService: ImportService?,
    private val migrationLifecycleGate: MigrationLifecycleGate,
) {
    // Per-pod mutual exclusion for the Git re-read: updateConfigCache() ->
    // gitResolver.updateCache() deletes and recreates the shared Git work dir
    // (GitVcsServiceImpl.cloneComponentsRegistry), so two concurrent PUTs would
    // race on it. Single-pod scope, like MigrationLifecycleGate.
    private val refreshInProgress = AtomicBoolean(false)

    /**
     * Phase-aware VCS-refresh endpoint.
     *
     * While any component is still served from Git, those components live in the
     * boot-time in-memory Git cache, so a manual re-read is still meaningful:
     * re-read and return HTTP 200 with the refresh duration (ms) — exactly the
     * pre-v3 behaviour — so v1/v2/v3 keep working like the old version throughout
     * the hybrid (pre-cutover) period.
     *
     * The endpoint is retired with **410 Gone** ONLY when we can positively
     * confirm full migration: the Git resolver parsed components (`total > 0`)
     * AND none are still git-only (`git == 0`, where `git` is the set of DSL
     * component keys NOT present as `source='db'` rows). One case deliberately
     * does NOT retire and instead attempts the refresh (the recovery action):
     *  - `total == 0` — Git status is indeterminate (the resolver returned empty
     *    or failed to load); falsely returning 410 here would block the very
     *    re-read an operator needs to recover the cache.
     *
     * `git` is now a set difference and so is always `>= 0`; extra db-only rows
     * (e.g. components created via the v4 write API after migration) no longer
     * push it negative the way the old `gitResolver.size - countBySource("db")`
     * subtraction did. The controller still treats a (now-unreachable) negative
     * defensively — `git == 0` is the only retire trigger.
     *
     * Refused with **409** when (a) a COMPONENTS migration is running (it reads
     * from the Git resolver to validate each component, so swapping the in-memory
     * config under it would race the import), or (b) another refresh is already
     * in progress on this pod.
     */
    @PutMapping("updateCache")
    @Operation(summary = "Re-read Git config while git-sourced components remain; 410 Gone once fully migrated to DB")
    fun updateConfigCache(): ResponseEntity<Any> {
        val activeJob = migrationLifecycleGate.current()
        if (activeJob?.kind == MigrationLifecycleGate.JobKind.COMPONENTS) {
            return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body<Any>(ErrorResponse("A components migration is in progress; retry updateCache after it completes"))
        }
        if (!refreshInProgress.compareAndSet(false, true)) {
            return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body<Any>(ErrorResponse("A cache refresh is already in progress; retry updateCache after it completes"))
        }
        try {
            // DB-mode only: getMigrationStatus() reads countBySource("db"). In no-db
            // mode ImportService is absent (importService == null) — there is no
            // migration that can be "fully done", so always fall through to the Git
            // re-read and return HTTP 200, exactly like the pre-v3 endpoint.
            val status = importService?.getMigrationStatus()
            // status.total = git-resolver component count (0 if it errored/empty);
            // status.git = DSL component keys not yet in the DB (set difference, always >= 0).
            // Retire only on a confirmed fully-migrated state; otherwise attempt the refresh.
            val fullyMigrated = status != null && status.total > 0 && status.git == 0L
            if (!fullyMigrated) {
                return ResponseEntity.ok<Any>(componentsRegistryService.updateConfigCache())
            }
            log.warn("PUT /rest/api/2/components-registry/service/updateCache is no longer supported; use POST /rest/api/4/admin/migrate")
            return ResponseEntity
                .status(HttpStatus.GONE)
                .body<Any>(ErrorResponse("updateCache is no longer supported; use POST /rest/api/4/admin/migrate"))
        } finally {
            refreshInProgress.set(false)
        }
    }

    @GetMapping("status", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getComponentsRegistryStatus(): ServiceStatusDTO = componentsRegistryService.getComponentsRegistryStatus()

    @GetMapping("ping")
    fun ping(): String = "Pong"

    companion object {
        private val log = LoggerFactory.getLogger(ComponentsRegistryServiceController::class.java)
    }
}
