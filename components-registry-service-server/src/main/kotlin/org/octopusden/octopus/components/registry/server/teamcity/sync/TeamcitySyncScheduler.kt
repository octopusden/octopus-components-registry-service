package org.octopusden.octopus.components.registry.server.teamcity.sync

import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.service.MigrationConflictException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Weekly cron that resyncs TC project ids/urls.
 *
 * Conditional bean, registered only when `teamcity.sync.enabled=true` in service-config;
 * otherwise the bean doesn't exist and the cron never fires (the default for CI/test envs
 * without TC credentials). Cron schedule is configurable via `teamcity.sync.cron`,
 * defaulting to Sunday 04:00 UTC to avoid typical CI/release windows.
 *
 * Goes through [TeamcitySyncJobService.startAsync] rather than [TeamcitySyncService.resync]
 * directly so the cron-fired sync shares the same concurrency gate as admin-triggered runs
 * and the components/history migration jobs: an already-RUNNING sync makes the cron skip
 * the week instead of fighting the manual run, and a running migration skips it with a
 * structured cross-kind warning.
 *
 * Failures from the resync (or from `startAsync` itself) are logged-and-swallowed so a
 * transient TC outage or live migration doesn't crash the bean and block next week's run.
 */
@ConditionalOnDatabaseEnabled
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "teamcity.sync", name = ["enabled"], havingValue = "true")
class TeamcitySyncScheduler(
    private val teamcitySyncJobService: TeamcitySyncJobService,
) {
    private val log = KotlinLogging.logger {}

    private companion object {
        // Actor stamped on the SYS-060 service-event journal row for cron-fired runs
        // (vs a username for admin-triggered ones).
        const val TRIGGERED_BY_SCHEDULER = "scheduler"
    }

    @Scheduled(cron = "\${teamcity.sync.cron:0 0 4 * * SUN}", zone = "UTC")
    @Suppress("TooGenericExceptionCaught")
    fun weeklyResync() {
        log.info { "TC sync: weekly cron firing" }
        try {
            val outcome = teamcitySyncJobService.startAsync(TRIGGERED_BY_SCHEDULER)
            if (outcome.isNewlyStarted) {
                log.info { "TC sync: weekly cron started job ${outcome.state.id}" }
            } else {
                // Same-kind attach: a TC resync is already RUNNING (likely an admin-triggered
                // one). Skip the week rather than block on someone else's job.
                log.warn {
                    "TC sync: weekly cron skipped — TC resync ${outcome.state.id} already RUNNING in this pod"
                }
            }
        } catch (e: MigrationConflictException) {
            // Cross-kind: components or history migration holds the gate. Skip the week.
            log.warn { "TC sync: weekly cron skipped — ${e.active.kind} job ${e.active.jobId} is RUNNING" }
        } catch (e: Exception) {
            // Catching keeps next week's fire alive regardless of a transient failure here.
            // The resync itself runs on the executor thread and its failures are captured into
            // the job state, not rethrown — this catch only fires if `startAsync` itself fails
            // (e.g. RejectedExecutionException on pod shutdown).
            log.error(e) { "TC sync: weekly cron failed to start" }
        }
    }
}
