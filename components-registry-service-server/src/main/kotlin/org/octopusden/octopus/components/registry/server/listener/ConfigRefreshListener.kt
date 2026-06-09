package org.octopusden.octopus.components.registry.server.listener

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.service.impl.ConfigSyncService
import org.slf4j.LoggerFactory
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Re-syncs the admin config blobs into the DB cache after a Spring Cloud Config
 * refresh (`POST /admin/reload-config` → `ContextRefresher.refresh()`).
 *
 * `RefreshScopeRefreshedEvent` is published AFTER `EnvironmentChangeEvent`, by
 * which time `ConfigurationPropertiesRebinder` has already rebound the mutable
 * `AdminConfigProperties` bean — so `ConfigSyncService` reads the fresh profile.
 *
 * DB-mode only: in no-db mode there is no cache to write and no
 * [ConfigSyncService] bean.
 */
@ConditionalOnDatabaseEnabled
@Component
class ConfigRefreshListener(
    private val configSyncService: ConfigSyncService,
) {
    private val log = LoggerFactory.getLogger(ConfigRefreshListener::class.java)

    @EventListener(RefreshScopeRefreshedEvent::class)
    fun onRefresh() {
        log.info("ConfigSync: refresh event received, re-syncing admin config to DB cache")
        configSyncService.syncToCache()
    }
}
