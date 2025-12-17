package org.octopusden.octopus.components.registry.automation

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.slf4j.LoggerFactory

class ComponentsRegistryCommand : CliktCommand(name = "") {
    private val url by option(URL_OPTION, help = "Components Registry Service URL")
        .convert { it.trim() }
        .required()
        .check("$URL_OPTION is empty") { it.isNotBlank() }

    private val context by findOrSetObject { mutableMapOf<String, Any>() }

    override fun run() {
        logger.info("Components Registry Service URL: $url")
        try {
            val clientUrlProvider = object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String = url
            }
            val client = ClassicComponentsRegistryServiceClient(clientUrlProvider)
            context[LOGGER] = logger
            context[CLIENT] = client
        } catch (e: Exception) {
            logger.error("Failed to create ComponentsRegistryServiceClient: ${e.message}", e)
            throw ProgramResult(statusCode = 2)
        }
    }

    companion object {
        const val URL_OPTION = "--url"
        const val LOGGER = "logger"
        const val CLIENT = "client"

        private val logger = LoggerFactory.getLogger(ComponentsRegistryCommand::class.java.`package`.name)
    }
}
