package org.octopusden.octopus.components.registry.automation

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class ComponentsRegistryDownloadAndMoveCopyrightFile : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()

    private val componentName by option(COMPONENT_NAME, help = "Component name")
        .convert { it.trim() }.required()
        .check("$COMPONENT_NAME is empty") { it.isNotBlank() }

    private val targetDir by option(TARGET_DIR, help = "Target directory")
        .convert { it.trim() }.required()
        .check("$TARGET_DIR is empty") { it.isNotBlank() }

    private val client by lazy {
        context[ComponentsRegistryCommand.CLIENT] as ClassicComponentsRegistryServiceClient
    }
    private val logger by lazy {
        context[ComponentsRegistryCommand.LOGGER] as Logger
    }

    override fun run() {
        try {
            logger.info("Downloading '$componentName' copyright file")
            val response = client.getCopyrightByComponent(componentName)
            val body = response.body()
            val inputStream = body.asInputStream()

            when (response.status()) {
                HTTP_STATUS_OK -> {
                    logger.info("Moving '$componentName' copyright to '$targetDir'")
                    val fullPath = Paths.get(targetDir, DOWNLOADING_COPYRIGHT_FILE_NAME)

                    Files.copy(
                        inputStream,
                        fullPath,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    logger.info("Successfully downloaded copyright file by path '$targetDir' " +
                            "with name '$DOWNLOADING_COPYRIGHT_FILE_NAME'")
                }
                else -> {
                    logger.error("Failed to download and move '$componentName' copyright, reason: {}", inputStream)
                }
            }
        } catch (e: Exception) {
            logger.error("Error while saving of component '{}', reason: '{}'", componentName, e.message)
        }
    }

    companion object {
        private const val COMMAND = "download-and-move-copyright-file"

        private const val COMPONENT_NAME = "--component-name"
        private const val TARGET_DIR = "--target-dir"

        private const val HTTP_STATUS_OK = 200

        private const val DOWNLOADING_COPYRIGHT_FILE_NAME = "COPYRIGHT"
    }
}
