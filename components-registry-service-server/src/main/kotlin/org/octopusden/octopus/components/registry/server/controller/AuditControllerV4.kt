package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogFilter
import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogResponse
import org.octopusden.octopus.components.registry.server.service.AuditService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("rest/api/4/audit")
@PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_AUDIT')")
class AuditControllerV4(
    private val auditService: AuditService,
) {
    @GetMapping("/{entityType}/{entityId}")
    fun getEntityHistory(
        @PathVariable entityType: String,
        @PathVariable entityId: String,
        pageable: Pageable,
    ): Page<AuditLogResponse> = auditService.getEntityHistory(entityType, entityId, pageable)

    /**
     * SYS-036 filter params, all independently optional. `from`/`to` are ISO-8601
     * instants forming a half-open `[from, to)` window over `audit_log.changed_at`.
     */
    @GetMapping("/recent")
    fun getRecentChanges(
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) entityId: String?,
        @RequestParam(required = false) changedBy: String?,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        pageable: Pageable,
    ): Page<AuditLogResponse> {
        val filter =
            AuditLogFilter(
                entityType = entityType,
                entityId = entityId,
                changedBy = changedBy,
                source = source,
                action = action,
                from = from,
                to = to,
            )
        return auditService.getRecentChanges(filter, pageable)
    }
}
