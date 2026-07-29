package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.Operation
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.ServiceEventFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ServiceEventResponse
import org.octopusden.octopus.components.registry.server.service.ServiceEventQueryService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * SYS-060: read side of the operational service-event journal. IMPORT_DATA-gated,
 * mirroring [AdminControllerV4] (`@permissionEvaluator.canImport()`) — the portal
 * Admin "Events" tab lives behind the same operator permission.
 *
 * The write/ingest side (POST) is a SEPARATE, method-scoped-permitAll controller
 * ([ServiceEventIngestControllerV4]) so it can accept tokenless portal calls guarded
 * by a shared secret instead of a JWT.
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/admin/service-events")
@PreAuthorize("@permissionEvaluator.canImport()")
class ServiceEventControllerV4(
    private val serviceEventQueryService: ServiceEventQueryService,
) {
    /**
     * Paginated journal, newest first. `eventType` / `source` / `status` are optional
     * exact filters; `from`/`to` are ISO-8601 instants forming a half-open `[from, to)`
     * window over `started_at`.
     */
    @GetMapping
    @Operation(operationId = "listServiceEvents")
    fun list(
        @RequestParam(required = false) eventType: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        pageable: Pageable,
    ): Page<ServiceEventResponse> =
        serviceEventQueryService.find(
            ServiceEventFilter(
                eventType = eventType,
                category = category,
                source = source,
                status = status,
                from = from,
                to = to,
            ),
            pageable,
        )
}
