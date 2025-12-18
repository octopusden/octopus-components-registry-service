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

        val response = client.getCopyrightByComponent(componentName)
        val body = response.body() ?: run {
            val responseStatus = response.status()
            logger.error(
                "Failed to download '{}' copyright: empty response body, status={}",
                componentName,
                responseStatus
            )
            throw RuntimeException("Failed to download '$componentName' copyright. HTTP status: $responseStatus")
        }

        body.asInputStream().use { inputStream ->
            when (val responseStatus = response.status()) {
                HTTP_STATUS_OK -> {
                    val path = Paths.get(targetPath)
                    path.parent?.let { Files.createDirectories(it) }

                    Files.copy(
                        inputStream,
                        path,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    logger.info(
                        "Successfully downloaded copyright file to '{}'",
                        path.toAbsolutePath()
                    )
                }

                else -> {
                    val errorBody = inputStream.bufferedReader().readText()
                    logger.error(
                        "Failed to download '{}' copyright: status={}, body={}",
                        componentName,
                        responseStatus,
                        errorBody
                    )
                    throw RuntimeException("Failed to download '$componentName' copyright. HTTP status: $responseStatus")
                }
            }
        }
    }

    companion object {
        const val COMMAND = "download-copyright"

        const val COMPONENT_NAME = "--component-name"
        const val TARGET_PATH = "--target-path"

        private const val HTTP_STATUS_OK = 200
    }
}
