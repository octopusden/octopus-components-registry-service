package org.octopusden.octopus.components.registry.server.service.impl

import jakarta.annotation.PostConstruct
import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.model.ServiceStatus
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
    private val importService: ImportService,
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
            serviceStatus.cacheUpdatedAt,
            serviceStatus.serviceMode,
            serviceStatus.versionControlRevision,
        )

    @PostConstruct
    @Suppress("UnusedPrivateMember")
    private fun cloneVcsData() {
        updateConfigCache()
        if (properties.autoMigrate) {
            log.info("Auto-migrate enabled, migrating all components to database...")
            val result = importService.migrate()
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
