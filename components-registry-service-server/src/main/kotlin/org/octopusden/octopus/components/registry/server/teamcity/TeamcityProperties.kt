package org.octopusden.octopus.components.registry.server.teamcity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the TeamCity sync engine. Bound from `teamcity.*` in
 * application*.yml (defaults below).
 *
 * Defaults are intentionally inert: with `base-url` blank and `sync.enabled`
 * false, the [TeamcityClient] does not register HTTP, the [TeamcitySyncService]
 * skips all calls, and the [TeamcitySyncScheduler] does not fire — so dev /
 * unconfigured envs boot cleanly without TC credentials. Production wires
 * through `service-config` per env.
 */
@ConfigurationProperties(prefix = "teamcity")
class TeamcityProperties(
    /**
     * Base URL of the TC server, e.g. `https://teamcity.example.com`.
     * Trailing slashes are tolerated. Blank disables the integration —
     * [TeamcitySyncService] short-circuits and returns an empty result.
     */
    val baseUrl: String = "",
    /** TC service account username (HTTP Basic auth). */
    val username: String = "",
    /** TC service account password (HTTP Basic auth). */
    val password: String = "",
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
