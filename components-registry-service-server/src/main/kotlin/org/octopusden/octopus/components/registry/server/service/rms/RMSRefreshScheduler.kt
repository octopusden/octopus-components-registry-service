package org.octopusden.octopus.components.registry.server.service.rms

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
 *
 * Not registered at all while the feature is disabled. [RMSBuildParametersService.refresh] already
 * no-ops in that state, so the task would have nothing to do — but the trigger re-arms regardless
 * of what the task did, so registering it would leave a timer waking forever to schedule a no-op.
 * It also keeps a disabled environment from touching the service at all, which is what a test that
 * mocks the service expects (a background thread calling `scheduledRefresh`/`nextDelay` on a mock
 * races Mockito's stubbing state and fails whichever test is stubbing at that moment).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDatabaseEnabled
@ConditionalOnProperty("release-management-service.enabled", havingValue = "true")
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
