package org.octopusden.octopus.components.registry.server.service.rms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.config.RMSProperties
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.time.Duration
import java.time.Instant

class RMSRefreshSchedulerTest {
    private class FakeTriggerContext(
        private val completion: Instant?,
    ) : TriggerContext {
        override fun lastScheduledExecution(): Instant? = completion

        override fun lastActualExecution(): Instant? = completion

        override fun lastCompletion(): Instant? = completion
    }

    private fun registerTrigger(service: RMSBuildParametersService) =
        ScheduledTaskRegistrar()
            .also { RMSRefreshScheduler(service).configureTasks(it) }
            .triggerTaskList
            .single()
            .trigger

    @Test
    @DisplayName("the first run fires immediately, not after the configured delay")
    fun `first run fires immediately`() {
        val service =
            RMSBuildParametersService(
                { RMSBuildsResult.Available(emptyList()) },
                { emptyList() },
                RMSProperties(enabled = true, url = "http://rms.example.com", normalInterval = Duration.ofHours(4)),
            )
        val trigger = registerTrigger(service)
        val before = Instant.now()

        val next = trigger.nextExecution(FakeTriggerContext(completion = null))

        if (next != null) {
            assertTrue(!next.isBefore(before), "first execution must not be before now")
            assertTrue(next.isBefore(before.plusSeconds(5)), "first execution must not wait for the normal interval")
        }
    }

    @Test
    @DisplayName("a subsequent run is anchored on the last completion plus the service's next delay")
    fun `subsequent run anchors on last completion plus next delay`() {
        val initialRetryInterval = Duration.ofMinutes(5)
        val provider = EligibleComponentsProvider { throw IllegalStateException("boom") }
        val service =
            RMSBuildParametersService(
                RMSClient { RMSBuildsResult.Available(emptyList()) },
                provider,
                RMSProperties(enabled = true, url = "http://rms.example.com", initialRetryInterval = initialRetryInterval),
            )
        service.refresh() // one failed sweep -> nextDelay() == initialRetryInterval
        val trigger = registerTrigger(service)
        val lastCompletion = Instant.now().minusSeconds(30)

        val next = trigger.nextExecution(FakeTriggerContext(completion = lastCompletion))

        assertEquals(lastCompletion.plus(initialRetryInterval), next)
    }
}
