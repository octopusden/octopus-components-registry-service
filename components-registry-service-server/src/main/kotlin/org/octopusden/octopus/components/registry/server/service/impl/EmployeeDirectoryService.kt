package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.employee.client.EmployeeServiceClient
import org.octopusden.employee.client.common.exception.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/**
 * Resolution of an active-employee lookup.
 *
 * Drives the fail-open contract: only [INACTIVE] and [UNKNOWN] are hard
 * failures (reject the write / 400); [UNAVAILABLE] (transport/timeout) and
 * [DISABLED] (no client wired) never block — the caller logs a WARN and allows.
 */
enum class ActiveStatus {
    /** Employee exists and `active == true`. */
    ACTIVE,

    /** Employee exists but `active == false`. Hard failure. */
    INACTIVE,

    /** Employee does not exist (`NotFoundException`). Hard failure. */
    UNKNOWN,

    /** Transport/timeout/unexpected error talking to employee-service. Fail-open. */
    UNAVAILABLE,

    /** No employee-service client wired (flag off / blank URL). Fail-open. */
    DISABLED,
}

/**
 * Thin wrapper over the optional [EmployeeServiceClient] bean. The client is
 * resolved through an [ObjectProvider] so the rest of the code works whether or
 * not the bean exists (flag off ⇒ `getIfAvailable() == null` ⇒ [ActiveStatus.DISABLED]).
 *
 * The underlying client exposes ONLY an exact `getEmployee(username)` lookup —
 * there is no prefix/search endpoint. So [search] is a single exact probe
 * (0 or 1 result) and [statuses] is a per-username batch of exact lookups.
 *
 * All three methods are fail-open on the no-bean and transport-error paths:
 * they never throw to the caller. The validation layer decides whether a given
 * [ActiveStatus] blocks the write.
 */
@Service
class EmployeeDirectoryService(
    private val clientProvider: ObjectProvider<EmployeeServiceClient>,
) {
    /** True when an employee-service client bean is wired (flag on + non-blank URL). */
    fun isEnabled(): Boolean = clientProvider.getIfAvailable() != null

    /**
     * Resolve a single username's active status.
     *
     * - bean absent → [ActiveStatus.DISABLED]
     * - `NotFoundException` → [ActiveStatus.UNKNOWN]
     * - `active == false` → [ActiveStatus.INACTIVE]
     * - `active == true` → [ActiveStatus.ACTIVE]
     * - any other exception (transport/timeout) → [ActiveStatus.UNAVAILABLE]
     */
    @Suppress("TooGenericExceptionCaught")
    open fun isActive(username: String): ActiveStatus {
        val client = clientProvider.getIfAvailable() ?: return ActiveStatus.DISABLED
        return try {
            if (client.getEmployee(username).active) ActiveStatus.ACTIVE else ActiveStatus.INACTIVE
        } catch (e: NotFoundException) {
            log.debug("employee-service: '{}' not found ({})", username, e.message)
            ActiveStatus.UNKNOWN
        } catch (e: Exception) {
            // Fail-open hook: transport/timeout/5xx must NOT block the write.
            log.debug("employee-service: lookup of '$username' failed — treating as UNAVAILABLE (fail-open)", e)
            ActiveStatus.UNAVAILABLE
        }
    }

    /**
     * Batch (exact) status lookup for the UI badge endpoint.
     * Value semantics: `true` = active, `false` = inactive, `null` = unknown /
     * unavailable / disabled (the Portal renders no badge for `null`).
     */
    fun statuses(usernames: Collection<String>): Map<String, Boolean?> =
        usernames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .associateWith { username ->
                when (isActive(username)) {
                    ActiveStatus.ACTIVE -> true
                    ActiveStatus.INACTIVE -> false
                    ActiveStatus.UNKNOWN, ActiveStatus.UNAVAILABLE, ActiveStatus.DISABLED -> null
                }
            }

    /**
     * Backing for the picker's typeahead. The client has no prefix search, so
     * this is an exact `getEmployee` probe of the (trimmed) query: a single hit
     * `[{username, active}]` when the user exists, or an empty list when it does
     * not / the service is unavailable / disabled (fail-open).
     */
    fun search(query: String): List<EmployeeMatch> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return when (val status = isActive(trimmed)) {
            ActiveStatus.ACTIVE -> listOf(EmployeeMatch(trimmed, true))
            ActiveStatus.INACTIVE -> listOf(EmployeeMatch(trimmed, false))
            ActiveStatus.UNKNOWN, ActiveStatus.UNAVAILABLE, ActiveStatus.DISABLED -> {
                log.debug("employee-service: search probe for '{}' returned {}", trimmed, status)
                emptyList()
            }
        }
    }

    /**
     * Transport-chain health probe for monitoring surfaces (actuator indicator
     * and the Portal admin banner), with the failure detail. Looks up a synthetic
     * username that must not exist in the directory: a NOT-FOUND answer proves the
     * whole CRS → api-gateway → employee-service → directory chain end-to-end.
     *
     * On failure it captures the real downstream cause — the exception class and
     * message, which carries the HTTP status (e.g. "403 Forbidden") — so the
     * actuator reason can distinguish access-denied (401/403, e.g. a token missing
     * the `openid` scope) from unreachable, instead of a generic "see logs".
     *
     * Unlike the lookup paths this is NOT fail-open — its whole purpose is to
     * surface the failure — but it still never throws. Calls the client directly
     * (not via [isActive]) so the exception is observable rather than collapsed
     * into [ActiveStatus.UNAVAILABLE].
     */
    @Suppress("TooGenericExceptionCaught")
    fun probeHealth(): ProbeResult {
        val client = clientProvider.getIfAvailable()
            ?: return ProbeResult(IntegrationHealth.DISABLED, null)
        return try {
            // Any answer (even INACTIVE) proves the chain end-to-end.
            client.getEmployee(PROBE_USERNAME)
            ProbeResult(IntegrationHealth.UP, null)
        } catch (e: NotFoundException) {
            // The expected answer for the synthetic probe user: chain is alive.
            ProbeResult(IntegrationHealth.UP, null)
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            log.warn("employee-service health probe failed — {}", detail, e)
            ProbeResult(IntegrationHealth.DOWN, detail)
        }
    }

    /** Coarse health enum for callers that don't need the failure detail. */
    fun probe(): IntegrationHealth = probeHealth().health

    companion object {
        /**
         * Synthetic username for [probe]. Deliberately implausible so the
         * expected directory answer is NOT FOUND (mapped to UP); if someone
         * ever registers it, ACTIVE/INACTIVE still map to UP.
         */
        const val PROBE_USERNAME = "octopus-integration-health-probe"

        private val log = LoggerFactory.getLogger(EmployeeDirectoryService::class.java)
    }
}

/** Wire shape for the picker endpoint: `[{username, active}]`. */
data class EmployeeMatch(
    val username: String,
    val active: Boolean,
)

/**
 * Coarse integration health for monitoring surfaces. Unlike [ActiveStatus]
 * this is about the transport chain, not a particular employee:
 *
 * - [UP] — the directory answered (any answer, including "no such employee",
 *   proves the chain end-to-end);
 * - [DOWN] — the lookup failed before producing an answer (missing/bad
 *   credentials, gateway/service unreachable, directory backend down);
 * - [DISABLED] — no client wired (flag off / blank URL): intentional, not a
 *   failure.
 */
enum class IntegrationHealth {
    UP,
    DOWN,
    DISABLED,
}

/**
 * Probe outcome for monitoring: the coarse [IntegrationHealth] plus, on
 * [IntegrationHealth.DOWN], a short detail naming the real downstream cause
 * (exception class + message, which carries the HTTP status). `null` detail on
 * UP/DISABLED.
 */
data class ProbeResult(
    val health: IntegrationHealth,
    val detail: String?,
)
