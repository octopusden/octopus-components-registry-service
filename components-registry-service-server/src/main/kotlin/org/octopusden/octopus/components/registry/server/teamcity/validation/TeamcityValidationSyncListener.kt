package org.octopusden.octopus.components.registry.server.teamcity.validation

import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.teamcity.sync.TeamcitySyncCompletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** Starts a validation run after a successful sync — async and best-effort (errors never affect the sync). */
@ConditionalOnDatabaseEnabled
@Component
class TeamcityValidationSyncListener(
    private val teamcityValidationJobService: TeamcityValidationJobService,
    private val fetcher: EnrichedTcProjectFetcher,
) {
    private val log = KotlinLogging.logger {}

    @EventListener
    @Suppress("TooGenericExceptionCaught")
    fun onSyncCompleted(event: TeamcitySyncCompletedEvent) {
        try {
            // Drop cached enriched-fetch results before triggering validation: a manual run
            // earlier in the cache TTL window must not let this post-sync run see a stale
            // pre-sync TeamCity snapshot.
            fetcher.invalidateAll()
            val outcome = teamcityValidationJobService.startAsync(POST_SYNC_TRIGGER)
            log.info {
                val what = if (outcome.isNewlyStarted) "started" else "attached to already-running job"
                "Post-sync TC validation $what (after sync job ${event.jobId})"
            }
        } catch (e: Exception) {
            log.warn(e) { "Post-sync TC validation trigger skipped (after sync job ${event.jobId}): ${e.message}" }
        }
    }

    private companion object {
        const val POST_SYNC_TRIGGER = "post-sync"
    }
}
