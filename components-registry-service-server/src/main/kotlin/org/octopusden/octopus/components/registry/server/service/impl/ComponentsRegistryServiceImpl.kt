package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.server.model.ServiceStatus
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.service.ComponentsRegistryService
import org.octopusden.octopus.components.registry.server.service.VcsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*
import javax.annotation.PostConstruct
import kotlin.system.measureTimeMillis

@Service
class ComponentsRegistryServiceImpl(
    private val vcsService: VcsService,
    private val componentRegistryResolver: ComponentRegistryResolver,
    private val serviceStatus: ServiceStatus
) : ComponentsRegistryService {

    override fun updateConfigCache(): Long {
        log.info("Start update of Component Registry")
        val executionTime = measureTimeMillis {
            serviceStatus.versionControlRevision = vcsService.cloneComponentsRegistry()
            componentRegistryResolver.updateCache()
            serviceStatus.cacheUpdatedAt = Date()
        }
        log.info("Finished update of Component Registry, execution time: ${executionTime}ms")
        return executionTime
    }

    override fun getComponentsRegistryStatus(): ServiceStatusDTO {
        return ServiceStatusDTO(
            serviceStatus.cacheUpdatedAt,
            serviceStatus.serviceMode,
            serviceStatus.versionControlRevision
        )
    }

    @PostConstruct
    private fun cloneVcsData() {
        updateConfigCache()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ComponentsRegistryServiceImpl::class.java)
    }
}
