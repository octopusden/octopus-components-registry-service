package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.Operation
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
    private val importService: ImportService,
    private val migrationLifecycleGate: MigrationLifecycleGate,
) {
    /**
     * Phase-aware VCS-refresh endpoint.
     *
     * While any component is still served from Git (migration-status `git > 0`),
     * those components live in the boot-time in-memory Git cache, so a manual
     * re-read is still meaningful: re-read and return HTTP 200 with the refresh
     * duration (ms) — exactly the pre-v3 behaviour — so v1/v2/v3 keep working
     * like the old version throughout the hybrid (pre-cutover) period.
     *
     * Once every component is migrated to the DB (`git <= 0`) the cache is
     * always consistent with the live DB; the endpoint is retired and returns
     * 410 Gone with a pointer to `POST /rest/api/4/admin/migrate`. `git` is
     * `gitResolver.size - countBySource("db")`, so `<= 0` (not `== 0`) also
     * covers stale/extra `source='db'` rows that would push the count negative.
     *
     * A re-read is refused with 409 while a COMPONENTS migration is running:
     * that job reads from the Git resolver to validate each component, and
     * swapping the in-memory Git config under it would race the import.
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
        if (importService.getMigrationStatus().git > 0) {
            return ResponseEntity.ok<Any>(componentsRegistryService.updateConfigCache())
        }
        log.warn("PUT /rest/api/2/components-registry/service/updateCache is no longer supported; use POST /rest/api/4/admin/migrate")
        return ResponseEntity
            .status(HttpStatus.GONE)
            .body<Any>(ErrorResponse("updateCache is no longer supported; use POST /rest/api/4/admin/migrate"))
    }

    @GetMapping("status", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getComponentsRegistryStatus(): ServiceStatusDTO = componentsRegistryService.getComponentsRegistryStatus()

    @GetMapping("ping")
    fun ping(): String = "Pong"

    companion object {
        private val log = LoggerFactory.getLogger(ComponentsRegistryServiceController::class.java)
    }
}
