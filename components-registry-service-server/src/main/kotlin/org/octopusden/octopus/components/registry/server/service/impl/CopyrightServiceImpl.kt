package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.service.CopyrightService
import org.octopusden.octopus.escrow.config.ConfigHelper
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.nio.file.Files

@Service
class CopyrightServiceImpl(
    private val componentRegistryResolver: ComponentRegistryResolver,
    private val configHelper: ConfigHelper,
) : CopyrightService {

    override fun getCopyrightAsResource(component: String): Resource {
        logger.info("Getting copyright for component '{}'", component)

        val copyrightPath = configHelper.copyrightPath()
            ?: throw NotFoundException("Copyright path is not configured")

        val escrowModule = componentRegistryResolver.getComponentById(component)
            ?: throw NotFoundException("Component '$component' not found")

        val copyright = escrowModule.moduleConfigurations
            .firstOrNull()
            ?.copyright
            ?: throw NotFoundException("Component '$component' does not contains copyright")

        if (!correctCopyrightFileRegex.matches(copyright)) {
            throw IllegalStateException("Component '$component' copyright has invalid name")
        }

        val resolved = copyrightPath.resolve(copyright)
            .toAbsolutePath()
            .normalize()

        if (!Files.isRegularFile(resolved)) {
            throw IllegalStateException("Component '$component' copyright file is not a file")
        }

        logger.info("Copyright file resource successfully received!")

        return FileSystemResource(resolved)
    }

    companion object {
        private val correctCopyrightFileRegex = Regex("[a-zA-Z0-9_-]+")

        private val logger = LoggerFactory.getLogger(CopyrightServiceImpl::class.java)
    }
}
