package org.octopusden.octopus.components.registry.server.teamcity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Shared TeamCity config (`teamcity.*`); in `teamcity.common` so both `sync` and `validation` use it.
 * Inert by default (blank `base-url` / `sync.enabled=false`) so unconfigured envs boot cleanly.
 */
@ConfigurationProperties(prefix = "teamcity")
class TeamcityProperties(
    /**
     * Base URL of the TC server, e.g. `https://teamcity.example.com`.
     * Trailing slashes are tolerated. Blank is a misconfiguration —
     * `TeamcitySyncService` throws [IllegalStateException] so the caller
     * surfaces the error rather than silently returning all-NO_MATCH. Set via
     * `teamcity.base-url` in service-config (components-registry-service.yml).
     */
    val baseUrl: String = "",
    /** TC service account username (HTTP Basic auth). Set via service-config. */
    val username: String = "",
    /** TC service account password (HTTP Basic auth). Set via service-config. */
    val password: String = "",
    val sync: SyncProperties = SyncProperties(),
) {
    data class SyncProperties(
        /**
         * Master switch for the weekly cron + idle behavior. False by default
         * so dev/unconfigured envs don't fail at startup. Production sets it
         * via `teamcity.sync.enabled` in service-config.
         */
        val enabled: Boolean = false,
        /**
         * Spring cron expression for the weekly resync. Default: Sunday 04:00 UTC.
         */
        val cron: String = "0 0 4 * * SUN",
        /**
         * TeamCity build-template id used as the tie-breaker when several TC
         * projects share the same `COMPONENT_NAME` parameter: the project that
         * owns a buildType inheriting from this template wins. If none of the
         * tied projects has it, the component is left unchanged (counted as
         * `skipped_ambiguous`). Configurable via
         * `teamcity.sync.cd-release-template-id` for envs where the template
         * carries a different id.
         */
        val cdReleaseTemplateId: String = "CDRelease",
    )
}
