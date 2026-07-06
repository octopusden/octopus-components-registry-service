package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant

/**
 * SYS-061 — body of `POST /rest/api/4/admin/service-events`, used by the portal BFF
 * to report its own operational events (portal redeploys, validation sweeps) into the
 * shared journal. Portal events arrive already-terminal (no RUNNING lifecycle), so
 * `status` is COMPLETED/FAILED and the timestamps are supplied by the caller.
 *
 * `eventType` / `status` / `source` are parsed leniently against the server enums at
 * the controller boundary; unknown values → 400.
 */
data class ServiceEventIngestRequest(
    val eventType: String,
    val status: String,
    val source: String,
    val triggeredBy: String? = null,
    val serviceVersion: String? = null,
    val correlationId: String? = null,
    val summary: String? = null,
    val detail: Map<String, Any?>? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
)
