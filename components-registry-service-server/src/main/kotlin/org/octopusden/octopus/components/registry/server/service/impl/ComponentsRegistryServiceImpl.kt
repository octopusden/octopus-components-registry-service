package org.octopusden.octopus.components.registry.server.service.impl

import jakarta.annotation.PostConstruct
import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.model.ServiceStatus
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.service.ComponentsRegistryService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.VcsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.Date
import kotlin.system.measureTimeMillis

@Service
class ComponentsRegistryServiceImpl(
    private val vcsService: VcsService,
    private val componentRegistryResolver: ComponentRegistryResolver,
    private val serviceStatus: ServiceStatus,
    private val properties: ComponentsRegistryProperties,
    // Nullable: all three are part of the database layer (@ConditionalOnDatabaseEnabled /
    // JPA) and absent in no-db mode. Spring injects null for a Kotlin-nullable
    // constructor parameter when no candidate bean exists; in db-mode all are wired.
    private val importService: ImportService?,
    private val componentSourceRepository: ComponentSourceRepository?,
    private val configSyncService: ConfigSyncService?,
) : ComponentsRegistryService {
    override fun updateConfigCache(): Long {
        log.info("Start update of Component Registry")
        val executionTime =
            measureTimeMillis {
                serviceStatus.versionControlRevision = vcsService.cloneComponentsRegistry()
                componentRegistryResolver.updateCache()
                serviceStatus.cacheUpdatedAt = Date()
            }
        log.info("Finished update of Component Registry, execution time: ${executionTime}ms")
        return executionTime
    }

    override fun getComponentsRegistryStatus(): ServiceStatusDTO =
        ServiceStatusDTO(
            cacheUpdatedAt = serviceStatus.cacheUpdatedAt,
            serviceMode = serviceStatus.serviceMode,
            versionControlRevision = serviceStatus.versionControlRevision,
            defaultSource = properties.defaultSource,
            // null-safe: no ComponentSourceRepository in no-db mode → no DB-sourced components.
            dbComponentCount = componentSourceRepository?.countBySource("db") ?: 0L,
        )

    @PostConstruct
    @Suppress("UnusedPrivateMember")
    private fun cloneVcsData() {
        updateConfigCache()
        // Sync code-as-config admin blobs (field-config + component-defaults) from
        // service-config into the registry_config DB cache BEFORE any auto-migration:
        // migrate() reads component-defaults, and field-config enforcement must be live
        // the moment the service serves traffic. No-op in no-db mode (bean absent).
        configSyncService?.syncToCache()
        if (properties.autoMigrate) {
            log.info("Auto-migrate enabled, migrating all components to database...")
            // auto-migrate is a DB-mode-only operation, so ImportService is always present
            // here; the no-db profile forces auto-migrate=false, so this branch is unreachable
            // when the DB layer is absent.
            val result =
                checkNotNull(importService) {
                    "auto-migrate requires the database layer (ImportService bean); it must not be enabled in no-db mode"
                }.migrate()
            log.info(
                "Auto-migrate complete: ${result.components.migrated} migrated, " +
                    "${result.components.skipped} skipped, ${result.components.failed} failed",
            )
            requireMigrationSucceeded(result)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ComponentsRegistryServiceImpl::class.java)
    }
}

/**
 * Enforce the `ft-db` binary contract: if auto-migrate reports any failures,
 * abort. Otherwise the service would serve traffic in a partially-migrated
 * state where failed-to-migrate components are silently invisible under
 * `default-source=db`.
 */
internal fun requireMigrationSucceeded(result: org.octopusden.octopus.components.registry.server.service.FullMigrationResult) {
    check(result.components.failed == 0) {
        "Auto-migrate reported ${result.components.failed} of ${result.components.total} components failed; " +
            "refusing to start under ft-db — see the Auto-migrate log for the failure reasons per component"
    }
}
