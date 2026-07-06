package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.config.ServiceEventProperties
import org.octopusden.octopus.components.registry.server.dto.v4.ServiceEventIngestRequest
import org.octopusden.octopus.components.registry.server.service.ServiceEventRecorder
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * SYS-061: ingest side of the service-event journal. Lets the portal BFF report its
 * OWN operational events (portal redeploys, scheduled validation sweeps) into the
 * shared timeline so the Admin "Events" tab shows both services.
 *
 * Auth is a shared-secret header (`X-Service-Event-Token`), NOT a JWT: the portal
 * BFF calls CRS tokenless today (see the validation sweep's `RegistryClient`), and
 * this is the same internal-network pattern as the permitAll `/migration-status`
 * probe. The filter chain permits `POST /rest/api/4/admin/service-events` (see
 * [org.octopusden.octopus.components.registry.server.config.WebSecurityConfig]) and
 * the secret is verified HERE — a `@PreAuthorize` cannot read a header.
 *
 * FAIL-CLOSED: a blank/unset configured token rejects every call (403), so a
 * misconfiguration never opens the endpoint to the network. Constant-time compare
 * avoids leaking the token via timing. A leaked token only lets an attacker forge
 * journal rows (no component data is mutated); a stronger service-account/OIDC/mTLS
 * scheme is tracked as a post-cutover follow-up.
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/admin/service-events")
class ServiceEventIngestControllerV4(
    private val serviceEventRecorder: ServiceEventRecorder,
    private val properties: ServiceEventProperties,
) {
    @PostMapping
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Event accepted and recorded"),
        ApiResponse(responseCode = "400", description = "Unknown eventType/status/source, or a non-terminal / non-portal event"),
        ApiResponse(responseCode = "403", description = "Invalid or missing X-Service-Event-Token"),
    )
    fun ingest(
        @RequestHeader(name = HEADER_TOKEN, required = false) token: String?,
        @RequestBody request: ServiceEventIngestRequest,
    ): ResponseEntity<Any> {
        if (!tokenAccepted(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse("Invalid or missing service-event token", "forbidden"))
        }

        val type =
            parseEnum(request.eventType) { ServiceEventType.valueOf(it) }
                ?: return badRequest("Unknown eventType: ${request.eventType}")
        val status =
            parseEnum(request.status) { ServiceEventStatus.valueOf(it) }
                ?: return badRequest("Unknown status: ${request.status}")
        val source =
            ServiceEventSource.fromWire(request.source)
                ?: return badRequest("Unknown source: ${request.source}")

        // Ingest is for portal-owned, already-terminal events only. Reject a non-terminal
        // status (a RUNNING row ingested here would never be closed — CRS reconcile only
        // targets crs rows) and reject a non-portal source (a token holder reports its own
        // events, and crs rows are owned by this service's in-process emitters).
        if (status == ServiceEventStatus.RUNNING) {
            return badRequest("Ingested events must be terminal (COMPLETED/FAILED), not RUNNING")
        }
        if (source != ServiceEventSource.PORTAL) {
            return badRequest("Ingest accepts source=portal only (got: ${request.source})")
        }

        serviceEventRecorder.recordInstant(
            type = type,
            source = source,
            triggeredBy = request.triggeredBy,
            status = status,
            serviceVersion = request.serviceVersion,
            correlationId = request.correlationId,
            summary = request.summary,
            detail = request.detail,
            startedAt = request.startedAt ?: java.time.Instant.now(),
            finishedAt = request.finishedAt,
        )
        LOG.debug("Ingested service-event: type={}, source={}, status={}", type, source, status)
        return ResponseEntity.accepted().build()
    }

    /** Fail-closed constant-time secret check. Blank configured token → always reject. */
    private fun tokenAccepted(presented: String?): Boolean {
        val expected = properties.ingestToken
        if (expected.isBlank() || presented.isNullOrEmpty()) return false
        // Compare fixed-length SHA-256 digests so neither the boolean result nor the timing
        // leaks the token length (MessageDigest.isEqual short-circuits on unequal array lengths).
        return MessageDigest.isEqual(sha256(presented), sha256(expected))
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))

    private inline fun <T> parseEnum(
        raw: String,
        parse: (String) -> T,
    ): T? = runCatching { parse(raw.trim().uppercase(java.util.Locale.ROOT)) }.getOrNull()

    private fun badRequest(message: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErrorResponse(message, "bad-request"))

    companion object {
        private val LOG = LoggerFactory.getLogger(ServiceEventIngestControllerV4::class.java)
        const val HEADER_TOKEN = "X-Service-Event-Token"
    }
}
