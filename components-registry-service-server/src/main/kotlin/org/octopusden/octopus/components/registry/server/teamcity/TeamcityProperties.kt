package org.octopusden.octopus.components.registry.server.teamcity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the TeamCity sync engine. Bound from `teamcity.*` in
 * application*.yml (defaults below).
 *
 * Defaults are intentionally inert: with `base-url` blank and `sync.enabled`
 * false, the [TeamcitySyncScheduler] bean is not registered — so dev /
 * unconfigured envs boot cleanly without TC credentials. The manual admin
 * resync endpoint will throw when `base-url` is blank, which is the intended
 * behaviour (surfaces misconfiguration). Production wires through
 * `service-config` per env.
 */
@ConfigurationProperties(prefix = "teamcity")
class TeamcityProperties(
    /**
     * Base URL of the TC server, e.g. `https://teamcity.example.com`.
     * Trailing slashes are tolerated. Blank is a misconfiguration —
     * [TeamcityClient] throws [IllegalStateException] so the caller surfaces
     * the error rather than silently returning all-NO_MATCH. Set via
     * `TEAMCITY_BASE_URL` environment variable.
     */
    val baseUrl: String = "",
    /** TC service account username (HTTP Basic auth). */
    val username: String = "",
    /** TC service account password (HTTP Basic auth). */
    val password: String = "",
    /**
     * TCP connect timeout for the TC HTTP client, in seconds. Bounded so a
     * stalled TC host does not hang resync indefinitely.
     */
    val connectTimeoutSeconds: Long = 10,
    /**
     * Per-request read timeout for the TC HTTP client, in seconds. Default
     * leaves headroom for a full bulk-projects response on a busy TC server
     * but is finite so resync cannot block forever on a slow read.
     */
    val readTimeoutSeconds: Long = 120,
    val sync: SyncProperties = SyncProperties(),
) {
    data class SyncProperties(
        /**
         * Master switch for the weekly cron + idle behavior. False by default
         * so dev/unconfigured envs don't fail at startup. Production sets it
         * via `TEAMCITY_SYNC_ENABLED` per env.
         */
        val enabled: Boolean = false,
        /**
         * Spring cron expression for the weekly resync. Default: Sunday 04:00 UTC.
         */
        val cron: String = "0 0 4 * * SUN",
    )
}
