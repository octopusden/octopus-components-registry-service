package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.config.FeedbackProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * SYS-062 retention: RESOLVED feedback (with its screenshot `bytea`) grows unbounded
 * otherwise. Deletes RESOLVED reports last touched more than `retention-days` ago,
 * daily. `retention-days <= 0` disables the prune (FeedbackServiceImpl.prune returns 0).
 *
 * App-wide scheduling is already switched on by `ServiceEventRetentionScheduler`
 * (`@EnableScheduling`); this bean only registers another `@Scheduled` method.
 * `@ConditionalOnDatabaseEnabled` keeps it out of the no-db profile.
 */
@ConditionalOnDatabaseEnabled
@Component
class FeedbackRetentionScheduler(
    private val feedbackService: FeedbackService,
    private val properties: FeedbackProperties,
) {
    @Scheduled(cron = "\${components-registry.feedback.prune-cron:0 45 3 * * *}", zone = "UTC")
    fun prune() {
        val pruned = feedbackService.prune()
        if (pruned > 0) {
            LOG.info("Pruned {} RESOLVED feedback row(s) older than {} days", pruned, properties.retentionDays)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(FeedbackRetentionScheduler::class.java)
    }
}
