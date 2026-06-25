package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ANONYMOUS (permitAll) probe of whether a migration / resync job is currently
 * RUNNING in this pod.
 *
 * WHY PUBLIC: the portal's background validation sweep calls CRS WITHOUT a JWT
 * (no user token on a scheduled sweep) and must skip while a migration is in
 * flight — during a Git→DB migration the legacy v2/v3 resolver can serve
 * components with not-yet-migrated archived flags, which the sweep would
 * otherwise validate and report as spurious problems. The authoritative job
 * state lives behind the admin-gated /rest/api/4/admin API which the
 * tokenless sweep cannot read, hence this minimal, non-sensitive sibling on a
 * permitAll path. Only the boolean is load-bearing; kind is for logs/ops.
 *
 * TRANSITIONAL: this endpoint only matters during the DSL→DB migration era.
 * Once all components are DB-native and the legacy migration/resync jobs are
 * retired, this controller AND its permitAll rule in
 * [org.octopusden.octopus.components.registry.server.config.WebSecurityConfig]
 * can be deleted.
 *
 * Single-pod scope: backed by [MigrationLifecycleGate]'s in-memory
 * AtomicReference (same caveat as the admin job endpoints) — if CRS runs with
 * replicas > 1 during a migration, a probe may hit a non-migrating pod. Making
 * the gate DB-backed is the documented followup (MigrationLifecycleGate KDoc).
 */
@RestController
@RequestMapping("rest/api/4/migration-status")
class MigrationStatusControllerV4(
    private val migrationLifecycleGate: MigrationLifecycleGate,
) {
    @GetMapping
    fun migrationStatus(): MigrationStatusResponse {
        val active = migrationLifecycleGate.current()
        return MigrationStatusResponse(running = active != null, kind = active?.kind?.name)
    }
}

/**
 * Minimal, non-sensitive migration-activity signal.
 *
 * - [running] — true iff a migration / resync job holds the lifecycle gate.
 * - [kind] — which job kind holds it (COMPONENTS / HISTORY / TC_RESYNC), or null
 *   when nothing is running. Informational only.
 */
data class MigrationStatusResponse(
    val running: Boolean,
    val kind: String?,
)
