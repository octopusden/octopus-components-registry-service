package org.octopusden.octopus.components.registry.server.config

import jakarta.validation.constraints.AssertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Configuration for the RMS-registered build-parameters feature (`release-management-service.*`).
 * Inert by default (`enabled=false`, blank `url`) so unconfigured environments boot cleanly.
 */
@Validated
@ConfigurationProperties(prefix = "release-management-service")
class RmsProperties(
    val enabled: Boolean = false,
    val url: String = "",
    /** Sweep cadence after a successful refresh. */
    val normalInterval: Duration = Duration.ofHours(4),
    /** Sweep cadence after the first consecutive failure; doubles on each further failure. */
    val initialRetryInterval: Duration = Duration.ofMinutes(5),
    /** Upper bound the doubling retry cadence never exceeds. */
    val retryBackoffCap: Duration = normalInterval,
) {
    @AssertTrue(message = "release-management-service.url must be set when release-management-service.enabled=true")
    fun isUrlConfiguredWhenEnabled(): Boolean = !enabled || url.isNotBlank()
}
