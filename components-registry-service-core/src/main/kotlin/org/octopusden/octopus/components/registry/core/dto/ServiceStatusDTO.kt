package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceStatusDTO @JsonCreator constructor(
    @JsonProperty("cacheUpdatedAt") val cacheUpdatedAt: Date,
    @JsonProperty("serviceMode") val serviceMode: ServiceMode,
    @JsonProperty("versionControlRevision") val versionControlRevision: String?,
    @JsonProperty("defaultSource") val defaultSource: String? = null,
    @JsonProperty("dbComponentCount") val dbComponentCount: Long? = null,
)
