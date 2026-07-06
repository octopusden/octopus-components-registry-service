package org.octopusden.octopus.components.registry.server.teamcity

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
 * Conditional bean — registered only when `teamcity.sync.enabled=true`
 * (`teamcity.sync.enabled=true` in service-config). With the property absent
 * or false (the default), the bean is not registered and Spring's
 * `@EnableScheduling` task scheduler ignores the cron entirely. Production
 * service-config sets the property per env that has TC credentials wired
 * through; CI/test envs never set it, so the scheduler never fires there.
 *
 * Cron is configurable via `teamcity.sync.cron`, defaulting to Sunday 04:00
 * UTC — far from typical CI/release windows so a long sync can't starve
 * normal traffic.
 *
 * Goes through [TeamcitySyncJobService.startAsync] rather than calling
 * [TeamcitySyncService.resync] directly so the cron-fired sync participates
 * in the same shared concurrency gate as admin-triggered runs (and as the
 * components / history migration jobs). This means:
 *  - if an admin already pressed the SPA Resync button and the job is still
 *    RUNNING when the cron fires, the cron skips the week instead of fighting
 *    the manual run;
 *  - if a components or history migration is RUNNING, the cron skips with
 *    a structured cross-kind exception logged at WARN.
 *
 * Failures from the underlying resync (or from `startAsync` itself) are
 * logged-and-swallowed so a transient TC outage / live migration does not
 * crash the bean and prevent next week's run.
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
                // Same-kind attach — a TC resync is already RUNNING in this
                // pod, almost certainly because an admin pressed the Resync
                // button before the cron fired. Skip the week; do not block
                // on someone else's job.
                log.warn {
                    "TC sync: weekly cron skipped — TC resync ${outcome.state.id} already RUNNING in this pod"
                }
            }
        } catch (e: MigrationConflictException) {
            // Cross-kind: components or history migration holds the gate.
            // Skip the week; the operator will see this in logs if it
            // happens repeatedly (which would be unusual — migrations
            // are not weekly events).
            log.warn { "TC sync: weekly cron skipped — ${e.active.kind} job ${e.active.jobId} is RUNNING" }
        } catch (e: Exception) {
            // A throw here would propagate up the @Scheduled task and the
            // task scheduler would suppress further runs only for the same
            // pod-lifetime in some configurations. Catching keeps the next
            // weekly fire alive regardless of TC's transient state. Note
            // the resync itself runs on the executor thread and its
            // failures are captured by [TeamcitySyncJobService] into the
            // job state, not rethrown here — so this catch fires only if
            // `startAsync` itself fails (e.g., RejectedExecutionException
            // on pod shutdown).
            log.error(e) { "TC sync: weekly cron failed to start" }
        }
    }
}
