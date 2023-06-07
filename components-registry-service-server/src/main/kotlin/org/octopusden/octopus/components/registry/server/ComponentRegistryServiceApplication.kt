package org.octopusden.octopus.components.registry.server

import org.octopusden.octopus.escrow.config.ConfigHelper
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.resolvers.JiraParametersResolver
import org.octopusden.octopus.escrow.resolvers.ModuleByArtifactResolver
import org.apache.commons.lang3.Validate
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.core.io.ResourceLoader
import java.io.File

@SpringBootApplication
@Import(ConfigHelper::class)
@Configuration
class ComponentRegistryServiceApplication {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Autowired
    private lateinit var environment: Environment

    @Bean
    fun customersMappingConfigUrl() =
        if (environment.containsProperty(ConfigHelper.PATH_TO_CONFIG))
            environment.getRequiredProperty(ConfigHelper.PATH_TO_CONFIG) + File.separator + ConfigHelper.CUSTOMERS_MAPPING_CONFIG_FILE
        else null

    @Bean
    fun configLoader(configHelper: ConfigHelper): ConfigLoader {
        val moduleConfigUrl = configHelper.moduleConfigUrl()
        Validate.notNull(moduleConfigUrl, "configName system property is not set")
        val resource = resourceLoader.getResource(moduleConfigUrl)
        Validate.notNull(
            resource,
            "cant load resource from moduleConfigUrl $moduleConfigUrl"
        )
        val url = resource.url
        return ConfigLoader(
            ComponentRegistryInfo.createFromURL(
                url
            )
        )
    }

    @Bean
    fun escrowConfigurationLoader(configLoader: ConfigLoader, configHelper: ConfigHelper) =
        EscrowConfigurationLoader(
            configLoader,
            configHelper.supportedGroupIds(),
            configHelper.supportedSystems()
        )

    @Bean
    fun componentInfoResolver() = JiraParametersResolver()

    @Bean
    fun jiraComponentVersionFormatter() = JiraComponentVersionFormatter()

    @Bean
    fun moduleByArtifactResolver() =
        ModuleByArtifactResolver()
}

fun main(args: Array<String>) {
    SpringApplication.run(ComponentRegistryServiceApplication::class.java, *args)
}
