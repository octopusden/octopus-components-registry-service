package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.*

/**
 * Operational metadata for the Components-Registry service.
 *
 * **Diagnostic surface only.** This DTO is NOT part of the strict v1/v2/v3 backward-
 * compatibility contract — additive fields are explicitly permitted. The compat-test
 * framework excludes `/rest/api/2/components-registry/service/status` from the
 * baseline `EndpointCoverageTest`; only environment-precondition probes read this
 * response (see `SnapshotPreconditionTest` in `components-registry-compat-test`).
 *
 * @property cacheUpdatedAt              timestamp of the last `updateConfigCache()` call.
 * @property serviceMode                 read-side mechanism: VCS (Git clone in-memory) or FS
 *                                        (no clone, schema-v2 path). Derived from `vcs.enabled`,
 *                                        NOT from `default-source` (see `ApplicationConfig`).
 * @property versionControlRevision      VCS revision the cloned DSL is pinned to; null if VCS
 *                                        not active or initial clone hasn't run.
 * @property defaultSource               components-registry `default-source` property (`"git"`
 *                                        or `"db"`) — the resolver fallback for components
 *                                        without an explicit `component_sources` row.
 *                                        **Nullable for backward compatibility**: old servers
 *                                        that predate this field simply omit it, Jackson
 *                                        deserialises as null.
 * @property dbComponentCount            number of components currently routed through the DB
 *                                        resolver (`component_sources.source = 'db'`). Used
 *                                        by env-preconditions to detect partial-migration
 *                                        states. **Nullable** for the same backward-compat
 *                                        reason as `defaultSource`.
 * @property configRevision              composite cache-actuality token `"[gitRevision].[maxId].[count]"`,
 *                                        where `maxId`/`count` are aggregates over `audit_log`
 *                                        excluding the git-history backfill. Moves when *either*
 *                                        side of the hybrid prod config changes — a new VCS
 *                                        revision, or a portal/DB write — so the Jira releng
 *                                        plugin can invalidate its cache even while
 *                                        `versionControlRevision` is frozen (OCTOPUS-2472).
 *                                        **Nullable**: `null` in no-db mode and on Git-based
 *                                        installations (no `AuditLogRepository`), so those paths
 *                                        see no behaviour change; old servers omit it (Jackson
 *                                        reads null), same backward-compat contract as above.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceStatusDTO(
    @JsonProperty("cacheUpdatedAt") val cacheUpdatedAt: Date,
    @JsonProperty("serviceMode") val serviceMode: ServiceMode,
    @JsonProperty("versionControlRevision") val versionControlRevision: String?,
    @JsonProperty("defaultSource") val defaultSource: String? = null,
    @JsonProperty("dbComponentCount") val dbComponentCount: Long? = null,
    @JsonProperty("configRevision") val configRevision: String? = null,
)
