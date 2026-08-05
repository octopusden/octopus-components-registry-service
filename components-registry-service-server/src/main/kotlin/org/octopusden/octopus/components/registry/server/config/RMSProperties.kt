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
class RMSProperties(
    val enabled: Boolean = false,
    val url: String = "",
    /** Sweep cadence after a successful refresh. */
    val normalInterval: Duration = Duration.ofHours(4),
    /** Sweep cadence after the first consecutive failure; doubles on each further failure. */
    val initialRetryInterval: Duration = Duration.ofMinutes(5),
    /** Upper bound the doubling retry cadence never exceeds. */
    val retryBackoffCap: Duration = normalInterval,
    /** Per-call HTTP connect timeout, applied to every request the client makes. */
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** Per-call HTTP read timeout, applied to every request the client makes. */
    val readTimeout: Duration = Duration.ofSeconds(10),
    /** Bound on concurrent in-flight RMS calls during a sweep. */
    val sweepConcurrency: Int = 8,
    /** Overall budget for one full sweep; components not completed within it count as unavailable. */
    val sweepTimeout: Duration = Duration.ofMinutes(5),
) {
    @AssertTrue(message = "release-management-service.url must be set when release-management-service.enabled=true")
    fun isUrlConfiguredWhenEnabled(): Boolean = !enabled || url.isNotBlank()
}
