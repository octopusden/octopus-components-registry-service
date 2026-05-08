package org.octopusden.octopus.components.registry.server.teamcity

import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.event.AuditEvent
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
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
 *      - 0 matches → leave nulls, count `skippedNoMatch`.
 *      - 1 match with non-blank webUrl → upsert id+url if changed; count
 *        `updated` or `unchanged`.
 *      - 1 match with blank webUrl → treat as no-match (cannot link).
 *      - 2+ matches → CDRelease tie-break:
 *          - filter to projects with `hasCdReleaseBuild = true`;
 *          - if filtered is empty → leave nulls, count `skippedAmbiguous`
 *            (no release build → no automated way to pick the right project);
 *          - if non-empty → pick the lexicographically smallest by id (stable
 *            across runs), apply the match, count it under `updated`/`unchanged`
 *            and bump `ambiguousAutoResolved` for visibility.
 *        Manual override remains the escape hatch for rows that still skip.
 *        TODO: properly support multiple TC projects per component (DTO/UI work).
 *
 * Idempotent: only writes when `id` or `url` actually changes. Audit log
 * emitted via existing [AuditEvent] flow when fields change so admins can
 * trace the source of writes.
 *
 * Error handling: a fetcher failure (TC unreachable, auth refused, malformed
 * response) propagates out of [resync] — for the admin endpoint that surfaces
 * as 502/500, for the scheduled cron that is logged-and-swallowed by the
 * scheduler. Per-write JPA failures inside the transaction template cause the
 * whole transaction to roll back, leaving DB state untouched.
 */
@Service
class TeamcitySyncService(
    private val componentRepository: ComponentRepository,
    private val tcProjectFetcher: TcProjectFetcher,
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
     * (either all changes commit, or none do on JPA failure), and the audit
     * events published BEFORE_COMMIT remain in the same tx as their row writes.
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
                .groupBy { it.name }
                .mapValues { (name, group) ->
                    if (group.size > 1) {
                        log.warn { "TC sync: duplicate component name '$name' in non-archived set; only first will be synced" }
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
                val candidates = matches[componentId].orEmpty()
                val pick: TcProject?
                val pickedFromAmbiguous: Boolean
                when {
                    candidates.isEmpty() -> {
                        pick = null
                        pickedFromAmbiguous = false
                    }
                    candidates.size == 1 -> {
                        pick = candidates.single()
                        pickedFromAmbiguous = false
                    }
                    else -> {
                        pick = resolveAmbiguous(component, componentId, candidates)
                        pickedFromAmbiguous = pick != null
                    }
                }

                when {
                    candidates.isEmpty() -> {
                        skippedNoMatch++
                    }
                    pick == null -> {
                        // Ambiguous and none of the candidates owns a CDRelease build —
                        // tie-break failed, fall through to skipped_ambiguous.
                        skippedAmbiguous++
                    }
                    else -> {
                        val isUsableUrl =
                            pick.webUrl.isNotBlank() &&
                                (pick.webUrl.startsWith("http://") || pick.webUrl.startsWith("https://"))
                        if (!isUsableUrl) {
                            // webUrl missing, blank, or non-http → cannot render
                            // a safe link. Treat as no-match: leave nulls, count it.
                            // ambiguousAutoResolved is NOT incremented here — the row is
                            // counted as skipped_no_match, not as a successful auto-resolve,
                            // so the result KDoc invariant "sub-counter of updated+unchanged"
                            // holds.
                            log.warn {
                                "TC sync: component '${component.name}' (id=$componentId) " +
                                    "matched TC project '${pick.id}' but webUrl " +
                                    "'${pick.webUrl}' is blank or not http/https; " +
                                    "treating as no-match."
                            }
                            skippedNoMatch++
                        } else {
                            val didChange = applyMatch(component, pick, changedBy)
                            if (didChange) updated++ else unchanged++
                            if (pickedFromAmbiguous) ambiguousAutoResolved++
                        }
                    }
                }
            } catch (e: Exception) {
                // Per-component error: log + count, keep processing the rest.
                // A top-level TC client failure short-circuits earlier and
                // propagates out.
                val msg = "Failed to sync component '${component.name}' (id=$componentId): ${e.message}"
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

    /**
     * Tie-break for `candidates.size > 1`: keep only those with a CDRelease build,
     * then pick the lexicographically smallest by id so the choice is stable
     * across reruns. Returns null when no candidate has a release build — caller
     * counts that as `skippedAmbiguous` (manual override is the escape hatch).
     *
     * Log-level policy:
     *  - `withCdRelease.size == 1` → INFO. The auto-pick is the *intended*
     *    outcome — exactly one project carries the release build, the other
     *    candidates are typically legacy/archived duplicates of the
     *    COMPONENT_NAME. Ops want this counted, not paged on.
     *  - `withCdRelease.size > 1` → WARN. Genuine ambiguity (multiple "release"
     *    projects share the same COMPONENT_NAME) where lexicographic tie-break
     *    is just a deterministic last-resort; the row needs a TC cleanup.
     *  - `withCdRelease.isEmpty()` → WARN. Skipped, ops should fix in TC.
     */
    private fun resolveAmbiguous(
        component: ComponentEntity,
        componentId: UUID,
        candidates: List<TcProject>,
    ): TcProject? {
        val withCdRelease = candidates.filter { it.hasCdReleaseBuild }
        if (withCdRelease.isEmpty()) {
            log.warn {
                "TC sync: ambiguous match for component '${component.name}' " +
                    "(id=$componentId): ${candidates.size} TC projects share " +
                    "COMPONENT_NAME=${component.name} but none has a CDRelease build " +
                    "(${candidates.joinToString { it.id }}) — skipping."
            }
            return null
        }
        // String.compareTo on TC ids: TC project ids are conventionally
        // [A-Za-z0-9_], so ASCII-lexicographic order is total and
        // case-insensitivity is moot. !! is safe: we returned above when
        // withCdRelease was empty, so minByOrNull cannot return null here.
        // (Kotlin 1.9.x deprecated `minBy` in favour of `minByOrNull`.)
        val pick = withCdRelease.minByOrNull { it.id }!!
        if (withCdRelease.size == 1) {
            log.info {
                "TC sync: ambiguous match for component '${component.name}' " +
                    "(id=$componentId): ${candidates.size} TC projects share " +
                    "COMPONENT_NAME=${component.name}, exactly one has a CDRelease build " +
                    "(${pick.id}); auto-picking it."
            }
        } else {
            log.warn {
                "TC sync: ambiguous match for component '${component.name}' " +
                    "(id=$componentId): ${candidates.size} TC projects share " +
                    "COMPONENT_NAME=${component.name}, ${withCdRelease.size} have a CDRelease build " +
                    "(${withCdRelease.joinToString { it.id }}); auto-picking '${pick.id}' " +
                    "(lexicographically smallest) — TC cleanup recommended."
            }
        }
        return pick
    }

    /**
     * Write the match if it differs from current state. Returns true when
     * either column actually changed (drives the `updated` counter and the
     * audit-log emission).
     */
    private fun applyMatch(
        component: ComponentEntity,
        match: TcProject,
        changedBy: String,
    ): Boolean {
        val oldId = component.teamcityProjectId
        val oldUrl = component.teamcityProjectUrl
        val newId = match.id
        val newUrl = match.webUrl

        if (oldId == newId && oldUrl == newUrl) {
            return false
        }

        component.teamcityProjectId = newId
        component.teamcityProjectUrl = newUrl
        // Save through the component repository to ensure the @Version
        // optimistic-locking column is bumped and any change-tracking
        // listeners fire as they would on a regular PATCH write.
        componentRepository.save(component)

        applicationEventPublisher.publishEvent(
            AuditEvent(
                entityType = "Component",
                entityId = component.id.toString(),
                action = "UPDATE",
                changedBy = changedBy,
                oldValue =
                    mapOf(
                        "teamcityProjectId" to oldId,
                        "teamcityProjectUrl" to oldUrl,
                    ),
                newValue =
                    mapOf(
                        "teamcityProjectId" to newId,
                        "teamcityProjectUrl" to newUrl,
                    ),
            ),
        )
        return true
    }
}
