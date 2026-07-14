package org.octopusden.octopus.components.registry.server.teamcity

import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentTeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentTeamcityProjectRepository
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The TeamCity sync engine.
 *
 * One pass:
 * 1. Read all non-archived components.
 * 2. Single batched TC REST call via [TcProjectFetcher]: ask for every project
 *    that carries a `COMPONENT_NAME` parameter (any value), then group the
 *    response client-side by the parameter value.
 * 3. For each component:
 *      - 0 matches → no write; existing rows are kept (clear via the v4 API).
 *        Counted `skippedNoMatch`.
 *      - ≥1 match → if any candidate has a `PROJECT_VERSION`, drop the null-version
 *        ones (nulls kept only when all are null). Group survivors by version and
 *        keep one project per line: single candidate wins; on ties prefer a
 *        `hasCdReleaseBuild` project then the smallest id; a tie with no release
 *        build leaves that line unresolved. Winners need a usable http(s) webUrl.
 *        Count the component once: `updated`/`unchanged` (+`ambiguousAutoResolved`
 *        if a line was tie-broken), else `skippedAmbiguous` (a line unresolved),
 *        else `skippedNoMatch`.
 *
 * Writes one row per PROJECT_VERSION line into the `component_teamcity_projects`
 * child table (each row stores `project_version`), ordered by version then id.
 *
 * Idempotent: only writes when the matched project id actually changes. The
 * change is traced via an INFO log line (so admins can find the source of a
 * write) but deliberately does NOT write an `audit_log` row: TeamCity sync is an
 * automated reconciliation, and one such row per re-linked component was noise
 * in the component history (SYS-051). `changedBy` comes from `CurrentUserResolver`
 * — `"system"` for the scheduled cron, or the admin's username when the resync is
 * triggered via an authenticated request. If per-sync auditing is ever wanted,
 * re-publish an `AuditEvent` here.
 *
 * Error handling: a fetcher failure (TC unreachable, auth refused, malformed
 * response) propagates out of [resync] — for the admin endpoint that surfaces
 * as 502/500, for the scheduled cron that is logged-and-swallowed by the
 * scheduler. Per-write JPA failures inside the transaction template cause the
 * whole transaction to roll back, leaving DB state untouched.
 */
@ConditionalOnDatabaseEnabled
@Service
class TeamcitySyncService(
    private val componentRepository: ComponentRepository,
    private val componentTeamcityProjectRepository: ComponentTeamcityProjectRepository,
    private val tcProjectFetcher: TcProjectFetcher,
    // Retained as the audit seam even though TeamCity sync is deliberately NOT
    // audited (SYS-051): TeamcitySyncServiceTest injects a recording publisher
    // and asserts no AuditEvent is published, guarding against an accidental
    // re-introduction. Re-publish here if per-sync auditing is ever wanted.
    @Suppress("UnusedPrivateProperty")
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val currentUserResolver: CurrentUserResolver,
    private val transactionTemplate: TransactionTemplate,
    private val teamcityProperties: TeamcityProperties,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Run one resync pass.
     *
     * The TC HTTP calls deliberately run OUTSIDE any database transaction so
     * a slow/stalled TC server cannot hold a JDBC connection or extend a
     * transaction's lifetime. Per-component writes happen inside a single
     * [TransactionTemplate.execute] block so the bulk write is still atomic
     * (either all changes commit, or none do on JPA failure). TC sync writes no
     * audit rows — the re-link is logged at INFO instead (SYS-051).
     */
    fun resync(): TeamcitySyncResult {
        check(teamcityProperties.baseUrl.isNotBlank()) {
            "TC sync is not configured: teamcity.base-url is blank. " +
                "Set teamcity.base-url in service-config (components-registry-service.yml)."
        }

        val components = componentRepository.findByArchivedFalse()
        val scanned = components.size
        log.info { "TC sync starting: scanned=$scanned non-archived components" }

        val componentsByName =
            components
                .filter { it.id != null }
                .groupBy { it.componentKey }
                .mapValues { (key, group) ->
                    if (group.size > 1) {
                        log.warn { "TC sync: duplicate componentKey '$key' in non-archived set; only first will be synced" }
                    }
                    group.first().id!!
                }

        // HTTP calls to TC happen here, deliberately OUTSIDE any DB tx.
        val matches = tcProjectFetcher.findByComponentNames(componentsByName)

        // CurrentUserResolver returns "system" when there is no auth context
        // (the scheduled cron path), or the JWT's preferred_username captured
        // from the admin request that started the async resync job. Either way
        // it never returns null.
        val changedBy = currentUserResolver.currentUsername()

        // execute() returns the callback's value; applyMatches never returns
        // null, so the !! cannot trip in practice — Kotlin just sees the API's
        // declared T? signature.
        return transactionTemplate.execute { _ -> applyMatches(components, matches, changedBy) }!!
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private fun applyMatches(
        components: List<ComponentEntity>,
        matches: Map<UUID, List<TcProject>>,
        changedBy: String,
    ): TeamcitySyncResult {
        val scanned = components.size
        var updated = 0
        var unchanged = 0
        var skippedNoMatch = 0
        var skippedAmbiguous = 0
        var ambiguousAutoResolved = 0
        val errors = mutableListOf<String>()

        for (component in components) {
            val componentId = component.id ?: continue
            try {
                val rawCandidates = matches[componentId].orEmpty()
                val candidates = if (rawCandidates.any { it.projectVersion != null }) {
                    rawCandidates.filter { it.projectVersion != null }
                } else {
                    rawCandidates
                }

                // Resolve each PROJECT_VERSION group to one winner.
                val resolutions = candidates
                    .groupBy { it.projectVersion }
                    .map { (version, group) -> resolveVersionGroup(component, componentId, version, group) }
                val ambiguousUnresolved = resolutions.any { it.ambiguousUnresolved }
                val resolvedPicks = resolutions.mapNotNull { it.pick }

                // URL guard: a pick we cannot render a safe http(s) link for is dropped.
                val (usablePicks, unusablePicks) = resolvedPicks.partition { it.project.isUsableUrl() }
                unusablePicks.forEach { p ->
                    log.warn {
                        "TC sync: component '${component.componentKey}' (id=$componentId) matched TC " +
                            "project '${p.project.id}' (version=${p.project.projectVersion}) but webUrl " +
                            "'${p.project.webUrl}' is blank or not http/https; dropping it."
                    }
                }

                when {
                    usablePicks.isNotEmpty() -> {
                        val didChange = applyMatch(component, usablePicks.map { it.project }, changedBy)
                        if (didChange) updated++ else unchanged++
                        // Sub-counter of updated+unchanged: at least one line needed a tie-break.
                        if (usablePicks.any { it.tieBroken }) ambiguousAutoResolved++
                    }

                    // No linkable winner: ambiguous if a line was left unresolved, else no-match.
                    ambiguousUnresolved -> {
                        skippedAmbiguous++
                    }

                    else -> {
                        skippedNoMatch++
                    }
                }
            } catch (e: Exception) {
                // Per-component error: log + count, keep processing the rest.
                // A top-level TC client failure short-circuits earlier and
                // propagates out.
                val msg = "Failed to sync component '${component.componentKey}' (id=$componentId): ${e.message}"
                log.error(e) { msg }
                errors.add(msg)
            }
        }

        log.info {
            "TC sync done: scanned=$scanned, updated=$updated, unchanged=$unchanged, " +
                "skippedNoMatch=$skippedNoMatch, skippedAmbiguous=$skippedAmbiguous, " +
                "ambiguousAutoResolved=$ambiguousAutoResolved, errors=${errors.size}"
        }
        return TeamcitySyncResult(
            scanned = scanned,
            updated = updated,
            unchanged = unchanged,
            skippedNoMatch = skippedNoMatch,
            skippedAmbiguous = skippedAmbiguous,
            ambiguousAutoResolved = ambiguousAutoResolved,
            errors = errors.toList(),
        )
    }

    /** A resolved winner for one PROJECT_VERSION line, with whether a tie-break was needed. */
    private data class Pick(
        val project: TcProject,
        val tieBroken: Boolean,
    )

    /**
     * Outcome of resolving a single PROJECT_VERSION group.
     * @property pick the chosen project, or null when the group could not be resolved.
     * @property ambiguousUnresolved true when the group had >1 candidate and none owns a
     *  CDRelease build, so no automated pick was possible (drives `skippedAmbiguous`).
     */
    private data class VersionResolution(
        val pick: Pick?,
        val ambiguousUnresolved: Boolean,
    )

    /** True when webUrl is a usable http(s) link we can safely render. */
    private fun TcProject.isUsableUrl(): Boolean = webUrl.isNotBlank() && (webUrl.startsWith("http://") || webUrl.startsWith("https://"))

    /**
     * Resolve one PROJECT_VERSION group to at most one project: a single candidate wins;
     * with several, keep those with a CDRelease build and take the smallest id. If none
     * has a release build the group is left unresolved (`ambiguousUnresolved = true`).
     * The auto-pick logs at INFO; genuine ambiguity (several release builds, or none) at WARN.
     */
    private fun resolveVersionGroup(
        component: ComponentEntity,
        componentId: UUID,
        projectVersion: String?,
        group: List<TcProject>,
    ): VersionResolution {
        if (group.size == 1) {
            return VersionResolution(Pick(group.single(), tieBroken = false), ambiguousUnresolved = false)
        }
        val withCdRelease = group.filter { it.hasCdReleaseBuild }
        if (withCdRelease.isEmpty()) {
            log.warn {
                "TC sync: ambiguous match for component '${component.componentKey}' (id=$componentId) " +
                    "version=$projectVersion: ${group.size} TC projects share COMPONENT_NAME and version " +
                    "but none has a CDRelease build (${group.joinToString { it.id }}) — skipping this line."
            }
            return VersionResolution(pick = null, ambiguousUnresolved = true)
        }
        // TC project ids are conventionally [A-Za-z0-9_], so ASCII-lexicographic order is
        // total. !! is safe: we returned above when withCdRelease was empty.
        val pick = withCdRelease.minByOrNull { it.id }!!
        if (withCdRelease.size == 1) {
            log.info {
                "TC sync: ambiguous match for component '${component.componentKey}' (id=$componentId) " +
                    "version=$projectVersion: exactly one of ${group.size} has a CDRelease build " +
                    "(${pick.id}); auto-picking it."
            }
        } else {
            log.warn {
                "TC sync: ambiguous match for component '${component.componentKey}' (id=$componentId) " +
                    "version=$projectVersion: ${withCdRelease.size} have a CDRelease build " +
                    "(${withCdRelease.joinToString { it.id }}); auto-picking '${pick.id}' " +
                    "(lexicographically smallest) — TC cleanup recommended."
            }
        }
        return VersionResolution(Pick(pick, tieBroken = true), ambiguousUnresolved = false)
    }

    /**
     * Reconcile the component's rows to exactly [matches] (one per PROJECT_VERSION line).
     * Rows are written ordered by version (nulls last) then id, so `sortOrder` is stable
     * and idempotency is a plain ordered compare of (projectId, projectVersion): unchanged
     * → no write, returns false; otherwise wipe all existing rows and insert the fresh set,
     * returns true. Traced to the log, not `audit_log` (SYS-051).
     */
    private fun applyMatch(
        component: ComponentEntity,
        matches: List<TcProject>,
        changedBy: String,
    ): Boolean {
        val componentId = component.id!!
        val existing = componentTeamcityProjectRepository.findByComponentId(componentId)

        val desired = matches.sortedWith(
            compareBy({ it.projectVersion == null }, { it.projectVersion.orEmpty() }, { it.id }),
        )
        val existingKeys = existing.sortedBy { it.sortOrder }.map { it.projectId to it.projectVersion }
        val desiredKeys = desired.map { it.id to it.projectVersion }
        if (existingKeys == desiredKeys) {
            // Nothing to do — same projects, same versions, same order.
            return false
        }

        if (existing.isNotEmpty()) componentTeamcityProjectRepository.deleteAllInBatch(existing)
        desired.forEachIndexed { index, tc ->
            componentTeamcityProjectRepository.save(
                ComponentTeamcityProjectEntity(
                    component = component,
                    projectId = tc.id,
                    projectVersion = tc.projectVersion,
                    sortOrder = index,
                ),
            )
        }

        log.info {
            "TeamCity sync re-linked component ${component.id}: " +
                "$existingKeys -> $desiredKeys (by $changedBy)"
        }
        return true
    }
}
