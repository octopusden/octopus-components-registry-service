package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * SYS-060/061 configuration for the operational service-event journal. Bound from
 * `components-registry.service-events.*` (defaults below); production wires the
 * ingest token through `service-config` per env.
 */
@ConfigurationProperties(prefix = "components-registry.service-events")
class ServiceEventProperties(
    /**
     * Shared secret required in the `X-Service-Event-Token` header on the portal
     * ingest endpoint (`POST /rest/api/4/admin/service-events`). BLANK BY DEFAULT →
     * ingest is disabled and every ingest call is rejected 403 (fail-closed): a
     * misconfigured/unset token never opens the endpoint to the network. The GET
     * read side is unaffected (JWT + IMPORT_DATA).
     */
    val ingestToken: String = "",
    /** Retention window in days; the scheduled prune deletes rows older than this. */
    val retentionDays: Long = 90,
    /** Cron for the retention prune (default 03:30 UTC daily — off the release/CI path). */
    val pruneCron: String = "0 30 3 * * *",
)
