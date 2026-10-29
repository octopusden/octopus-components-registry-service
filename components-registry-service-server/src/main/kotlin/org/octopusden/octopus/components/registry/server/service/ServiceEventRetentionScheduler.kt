package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.config.ServiceEventProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * SYS-060 retention: the `service_event` journal is append-only and grows on every
 * redeploy and every validation sweep, while a pod can run for weeks between releases
 * — so pruning must be SCHEDULED, not startup-only (a startup-only prune never runs on
 * a long-lived pod). Deletes rows older than `retention-days` daily (best-effort, via
 * the recorder). Delete-by-timestamp is idempotent, so it is safe even were it ever to
 * run on more than one pod; prod is single-pod regardless.
 *
 * Carries `@EnableScheduling` so the app-wide scheduler is active whenever the DB layer
 * is (the only other scheduled bean, `TeamcitySyncScheduler`, is gated on a TC property
 * and cannot be relied on to enable scheduling here). `@ConditionalOnDatabaseEnabled`
 * keeps it out of the no-db profile.
 */
@ConditionalOnDatabaseEnabled
@Component
@EnableScheduling
class ServiceEventRetentionScheduler(
    private val serviceEventRecorder: ServiceEventRecorder,
    private val properties: ServiceEventProperties,
) {
    @Scheduled(cron = "\${components-registry.service-events.prune-cron:0 30 3 * * *}", zone = "UTC")
    fun prune() {
        val cutoff = Instant.now().minus(Duration.ofDays(properties.retentionDays))
        val pruned = serviceEventRecorder.prune(cutoff)
        if (pruned > 0) {
            LOG.info("Pruned {} service-event row(s) older than {} days", pruned, properties.retentionDays)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ServiceEventRetentionScheduler::class.java)
    }
}
