package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.service.VcsService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

@Service
@ConditionalOnProperty(prefix = "components-registry.vcs", name = ["enabled"], havingValue = "false")
class NoOpVcsServiceImpl : VcsService {

    override fun cloneComponentsRegistry(): String? =
        null.also { log.debug("Service in File System mode, do nothing") }

    @PostConstruct
    fun postNoOpVcsServiceImplConstruct() {
        log.info("Service works in File System mode")
    }

    companion object {
        private val log = LoggerFactory.getLogger(NoOpVcsServiceImpl::class.java)
    }
}