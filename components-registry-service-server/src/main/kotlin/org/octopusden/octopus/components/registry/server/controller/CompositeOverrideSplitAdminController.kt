package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.CompositeOverrideSplitResult
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.CompositeOverrideSplitAbortedException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * SYS-066 — one-off admin endpoint that splits legacy composite field-override rows into
 * single-segment rows. Deliberately a SEPARATE controller (not a method on [AdminControllerV4]) so it
 * can be gated by a single `@ConditionalOnProperty`: with the flag unset the bean is not registered
 * (404), enforcing "our DB only" structurally and leaving every other admin endpoint untouched.
 * Same base path + auth (`canImport`, i.e. IMPORT_DATA) + DB-only condition as [AdminControllerV4].
 *
 * Default OFF; enable per-deployment with
 * `components-registry.composite-override-split.enabled=true` for the one-off run, then disable again.
 *
 * CONCURRENCY: this scans and rewrites configuration rows, which have no `@Version` optimistic lock,
 * under the default READ COMMITTED isolation. A field-override PATCH committing on a target component
 * DURING the write could be lost. This is not guarded with pessimistic locks (disproportionate for a
 * one-off, flag-gated tool); instead the operational contract is: run it in a MAINTENANCE WINDOW with
 * the registry quiesced (no concurrent component/override edits), flag on → dry-run → review → write →
 * flag off. The `manifestToken` guards against changes BETWEEN dry-run and write; quiescence guards the
 * write itself.
 */
@ConditionalOnDatabaseEnabled
@ConditionalOnProperty(
    name = ["components-registry.composite-override-split.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@RestController
@RequestMapping("rest/api/4/admin")
@PreAuthorize("@permissionEvaluator.canImport()")
class CompositeOverrideSplitAdminController(
    private val componentManagementService: ComponentManagementService,
) {
    /**
     * Preview ([dryRun]=true, the default) returns the manifest + a `manifestToken`. To write, call
     * again with `dryRun=false` and the token from the preview; the token is recomputed in-transaction
     * and compared, so the write aborts (409) if the data changed since review or the token is missing.
     */
    @PostMapping("/field-overrides/split-composites")
    fun splitComposites(
        @RequestParam(defaultValue = "true") dryRun: Boolean,
        @RequestParam(required = false) manifestToken: String?,
    ): ResponseEntity<CompositeOverrideSplitResult> =
        ResponseEntity.ok(componentManagementService.splitCompositeFieldOverrides(dryRun, manifestToken))

    @ExceptionHandler(CompositeOverrideSplitAbortedException::class)
    fun handleAborted(e: CompositeOverrideSplitAbortedException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("code" to "composite-split-aborted", "message" to (e.message ?: "Composite split aborted")),
        )
}
