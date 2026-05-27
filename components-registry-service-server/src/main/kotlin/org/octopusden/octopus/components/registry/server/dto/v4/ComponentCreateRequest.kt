package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * Create body for a new component. Mirrors the v2 schema row-for-row — no
 * `metadata: Map` catch-all. Top-level scalars map directly to `components`
 * columns; nested collections map to per-component child tables.
 *
 * Per-version configuration is split: the base row is supplied via
 * `baseConfiguration`; override rows are added afterwards via the
 * field-override API.
 *
 * **Strict contract (UI-swift-sloth)** — `ComponentManagementService`
 * rejects payloads with **400 Bad Request** when:
 *  - `group` is null, or `group.groupKey` is blank;
 *  - `baseConfiguration` is null, or `baseConfiguration.build` is null,
 *    or `baseConfiguration.build.buildSystem` is null/blank.
 *
 * In other words: although these fields are declared nullable at the
 * Kotlin / Jackson layer for backward-compatible deserialisation, every
 * created component MUST carry a group and a base build system. The
 * Portal's Create Component dialog enforces both at the UX layer; the
 * server is the source of truth.
 */
data class ComponentCreateRequest(
    val name: String,
    val displayName: String? = null,
    val componentOwner: String? = null,
    val productType: String? = null,
    // Single-value (see DTO KDoc): a component belongs to at most one system.
    // The legacy `systems: Set<String>` was widened to many during early DSL
    // import; business decision (post-#299) is exactly one. The column is
    // nullable so a component without a designated system stays expressible
    // until/if a strict-contract follow-up makes it required.
    val system: String? = null,
    val clientCode: String? = null,
    val solution: Boolean? = null,
    val parentComponentName: String? = null,
    val archived: Boolean = false,
    val releaseManager: String? = null,
    val securityChampion: String? = null,
    val copyright: String? = null,
    val releasesInDefaultBranch: Boolean? = null,
    val labels: Set<String> = emptySet(),
    val jiraDisplayName: String? = null,
    val jiraHotfixVersionFormat: String? = null,
    val vcsExternalRegistry: String? = null,
    val distributionExplicit: Boolean? = null,
    val distributionExternal: Boolean? = null,
    val group: ComponentGroupRequest? = null,
    val docs: List<DocLinkRequest> = emptyList(),
    val artifactIds: List<ArtifactIdRequest> = emptyList(),
    val securityGroups: List<SecurityGroupRequest> = emptyList(),
    val teamcityProjects: List<TeamcityProjectRequest> = emptyList(),
    val baseConfiguration: BaseConfigurationRequest? = null,
)
