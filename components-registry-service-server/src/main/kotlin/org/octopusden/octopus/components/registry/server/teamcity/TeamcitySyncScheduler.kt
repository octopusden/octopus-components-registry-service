package org.octopusden.octopus.components.registry.server.teamcity

import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Weekly cron that resyncs TC project ids/urls.
 *
 * Conditional bean — registered only when `teamcity.sync.enabled=true`
 * (`TEAMCITY_SYNC_ENABLED=true` in env). With the property absent or false
 * (the default), the bean is not registered and Spring's `@EnableScheduling`
 * task scheduler ignores the cron entirely. Production service-config sets
 * the property per env that has TC credentials wired through; CI/test envs
 * never set it, so the scheduler never fires there.
 *
 * Cron is configurable via `teamcity.sync.cron`, defaulting to Sunday 04:00
 * UTC — far from typical CI/release windows so a long sync can't starve
 * normal traffic.
 *
 * Failures are logged-and-swallowed so a transient TC outage does not crash
 * the bean and prevent next week's run.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "teamcity.sync", name = ["enabled"], havingValue = "true")
class TeamcitySyncScheduler(
    private val syncService: TeamcitySyncService,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(cron = "\${teamcity.sync.cron:0 0 4 * * SUN}", zone = "UTC")
    @Suppress("TooGenericExceptionCaught")
    fun weeklyResync() {
        log.info { "TC sync: weekly cron firing" }
        try {
            val result = syncService.resync()
            log.info { "TC sync weekly result: $result" }
        } catch (e: Exception) {
            // A throw here would propagate up the @Scheduled task and the
            // task scheduler would suppress further runs only for the same
            // pod-lifetime in some configurations. Catching keeps the next
            // weekly fire alive regardless of TC's transient state.
            log.error(e) { "TC sync weekly run failed" }
        }
    }
}
