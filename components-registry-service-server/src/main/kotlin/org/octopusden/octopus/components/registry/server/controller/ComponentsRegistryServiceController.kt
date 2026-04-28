package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.Operation
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.server.service.ComponentsRegistryService
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
) {
    /**
     * Legacy endpoint kept for URL compatibility only. In the DB-backed architecture the
     * cache is always consistent with the live DB, so a manual VCS refresh is meaningless
     * and the endpoint has been retired. Callers get HTTP 410 Gone and a pointer to the
     * replacement endpoint instead of a silent refresh that masks the migration.
     */
    @PutMapping("updateCache")
    @Operation(deprecated = true, summary = "Removed — use POST /rest/api/4/admin/migrate")
    fun updateConfigCache(): ResponseEntity<ErrorResponse> {
        log.warn("PUT /rest/api/2/components-registry/service/updateCache is no longer supported; use POST /rest/api/4/admin/migrate")
        return ResponseEntity
            .status(HttpStatus.GONE)
            .body(ErrorResponse("updateCache is no longer supported; use POST /rest/api/4/admin/migrate"))
    }

    @GetMapping("status", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getComponentsRegistryStatus(): ServiceStatusDTO = componentsRegistryService.getComponentsRegistryStatus()

    @GetMapping("ping")
    fun ping(): String = "Pong"

    companion object {
        private val log = LoggerFactory.getLogger(ComponentsRegistryServiceController::class.java)
    }
}
