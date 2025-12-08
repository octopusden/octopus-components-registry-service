package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.service.CopyrightService
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

@Service
class CopyrightServiceImpl(
    private val componentsRegistryProperties: ComponentsRegistryProperties,
    private val componentRegistryResolver: ComponentRegistryResolver,
) : CopyrightService {

    override fun getCopyrightAsResource(component: String): Resource {
        logger.info("Getting copyright for component '{}'", component)

        val copyrightPath = componentsRegistryProperties.copyrightPath
            ?.let { Paths.get(it) }
            ?: throw IllegalStateException("Copyright path is not configured")

        if (!Files.isDirectory(copyrightPath)) {
            throw IllegalStateException("Copyright path '$copyrightPath' is not a directory")
        }

        val escrowModule = componentRegistryResolver.getComponentById(component)
            ?: throw NotFoundException("Component '$component' not found")

        val copyright = escrowModule.moduleConfigurations
            .firstOrNull()
            ?.copyright
            ?: throw NotFoundException("Component '$component' does not contains copyright")

        val copyrightFilePath = copyrightPath.resolve(copyright)
        if (!Files.isRegularFile(copyrightFilePath)) {
            throw IllegalStateException("Component '$component' copyright file is not a file")
        }

        logger.info("Copyright file resource successfully received!")

        return FileSystemResource(copyrightFilePath)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CopyrightServiceImpl::class.java)
    }
}
