package org.octopusden.octopus.components.registry.server.service.rms

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar

/**
 * Registers the sweep with a dynamic [Trigger] instead of a static `@Scheduled(fixedDelay=…)`,
 * mirroring Portal's `ValidationRefreshScheduler`: the first run fires immediately, and every
 * subsequent gap is [RMSBuildParametersService.nextDelay] — the normal interval after a clean
 * sweep, a doubling retry cadence after a failed one. Absent in no-db mode along with the
 * required (non-nullable) [RMSBuildParametersService] itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDatabaseEnabled
class RMSRefreshScheduler(
    private val service: RMSBuildParametersService,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addTriggerTask(
            { service.scheduledRefresh() },
            Trigger { context: TriggerContext ->
                val lastCompletion = context.lastCompletion()
                if (lastCompletion == null) {
                    context.clock.instant()
                } else {
                    lastCompletion.plus(service.nextDelay())
                }
            },
        )
    }
}
