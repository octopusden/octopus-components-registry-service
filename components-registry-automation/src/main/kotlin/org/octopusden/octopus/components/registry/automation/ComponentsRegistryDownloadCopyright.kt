package org.octopusden.octopus.components.registry.automation

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.slf4j.Logger
import java.nio.file.Paths

class ComponentsRegistryDownloadCopyright : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()

    private val componentName by option(COMPONENT_NAME, help = "Component name")
        .convert { it.trim() }.required()
        .check("$COMPONENT_NAME is empty") { it.isNotBlank() }

    private val targetPath by option(TARGET_PATH, help = "Target path")
        .convert { it.trim() }.required()
        .check("$TARGET_PATH is empty") { it.isNotBlank() }

    private val client by lazy {
        context[ComponentsRegistryCommand.CLIENT] as ClassicComponentsRegistryServiceClient
    }
    private val logger by lazy {
        context[ComponentsRegistryCommand.LOGGER] as Logger
    }

    override fun run() {
        logger.info("Downloading '$componentName' copyright file")

        try {
            val copyrightContent = client.getCopyrightByComponent(componentName)
                .fileContent

            val path = Paths.get(targetPath, DEFAULT_COPYRIGHT_FILE_NAME)

            path.toFile().writeText(copyrightContent, Charsets.UTF_8)

            logger.info(
                "Successfully downloaded copyright file by path '{}' with name '{}'",
                targetPath,
                DEFAULT_COPYRIGHT_FILE_NAME
            )
        } catch (_: Exception) {
            logger.error("Failed to download '{}' copyright file", componentName)
        }
    }

    companion object {
        private const val COMMAND = "download-copyright"

        private const val COMPONENT_NAME = "--component-name"
        private const val TARGET_PATH = "--target-path"

        private const val DEFAULT_COPYRIGHT_FILE_NAME = "COPYRIGHT"
    }
}
