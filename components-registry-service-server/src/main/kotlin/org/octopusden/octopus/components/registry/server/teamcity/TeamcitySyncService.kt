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
 * 2. For each component, issue a TC REST call to find projects where
 *    `COMPONENT_NAME` parameter equals the component name (exact match,
 *    per-component query via [TcProjectFetcher]).
 * 3. For each component:
 *      - 0 matches → leave nulls, count `skippedNoMatch`.
 *      - 1 match with non-blank webUrl → upsert id+url if changed; count
 *        `updated` or `unchanged`.
 *      - 1 match with blank webUrl → treat as no-match (cannot link).
 *      - 2+ matches → skip, count `skippedAmbiguous`. Manual override is
 *        the escape hatch.
 *
 * Idempotent: only writes when `id` or `url` actually changes. Audit log
 * emitted via existing [AuditEvent] flow when fields change so admins can
 * trace the source of writes.
 *
 * Error handling: the production [TcProjectFetcher] implementation
 * ([ExternalTcProjectFetcher]) isolates per-component TC failures — a single
 * HTTP error is logged as a warning and that component is treated as no-match,
 * leaving the rest of the batch unaffected. Only a failure that aborts the
 * fetcher entirely (e.g. an alternative implementation used in tests, or an
 * unrecoverable initialisation error) propagates out of [resync] — for the
 * admin endpoint that surfaces as 502/500, for the scheduled cron that is
 * logged-and-swallowed by the scheduler.
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
        // (the scheduled cron path), or the JWT's preferred_username when an
        // admin invokes the resync endpoint. Either way it never returns null.
        val changedBy = currentUserResolver.currentUsername()

        // execute() returns the callback's value; applyMatches never returns
        // null, so the !! cannot trip in practice — Kotlin just sees the API's
        // declared T? signature.
        return transactionTemplate.execute { _ -> applyMatches(components, matches, changedBy) }!!
    }

    @Suppress("TooGenericExceptionCaught")
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
        val errors = mutableListOf<String>()

        for (component in components) {
            val componentId = component.id ?: continue
            try {
                val candidates = matches[componentId].orEmpty()
                when {
                    candidates.isEmpty() -> {
                        skippedNoMatch++
                    }
                    candidates.size > 1 -> {
                        // Don't pick a winner silently. Manual override is
                        // the documented escape hatch for genuinely-duplicated
                        // TC setups.
                        log.warn {
                            "TC sync: ambiguous match for component '${component.name}' " +
                                "(id=$componentId): ${candidates.size} TC projects share " +
                                "COMPONENT_NAME=${component.name} — skipping."
                        }
                        skippedAmbiguous++
                    }
                    else -> {
                        val match = candidates.single()
                        val isUsableUrl =
                            match.webUrl.isNotBlank() &&
                                (match.webUrl.startsWith("http://") || match.webUrl.startsWith("https://"))
                        if (!isUsableUrl) {
                            // webUrl missing, blank, or non-http → cannot render
                            // a safe link. Treat as no-match: leave nulls, count it.
                            log.warn {
                                "TC sync: component '${component.name}' (id=$componentId) " +
                                    "matched TC project '${match.id}' but webUrl " +
                                    "'${match.webUrl}' is blank or not http/https; " +
                                    "treating as no-match."
                            }
                            skippedNoMatch++
                        } else {
                            val didChange = applyMatch(component, match, changedBy)
                            if (didChange) updated++ else unchanged++
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
                "errors=${errors.size}"
        }
        return TeamcitySyncResult(
            scanned = scanned,
            updated = updated,
            unchanged = unchanged,
            skippedNoMatch = skippedNoMatch,
            skippedAmbiguous = skippedAmbiguous,
            errors = errors.toList(),
        )
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
