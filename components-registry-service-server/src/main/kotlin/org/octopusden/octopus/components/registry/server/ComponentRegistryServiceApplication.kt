package org.octopusden.octopus.components.registry.server

import org.octopusden.octopus.escrow.config.ConfigHelper
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.resolvers.JiraParametersResolver
import org.octopusden.octopus.escrow.resolvers.ModuleByArtifactResolver
import org.apache.commons.lang3.Validate
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
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
    fun configLoader(configHelper: ConfigHelper, versionNames: VersionNames): ConfigLoader {
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
            ), versionNames
        )
    }

    @Bean
    fun escrowConfigurationLoader(configLoader: ConfigLoader, configHelper: ConfigHelper, versionNames: VersionNames) =
        EscrowConfigurationLoader(
            configLoader,
            configHelper.supportedGroupIds(),
            configHelper.supportedSystems(),
            versionNames
        )

    @Bean
    fun componentInfoResolver(versionNames: VersionNames) = JiraParametersResolver(versionNames)

    @Bean
    fun jiraComponentVersionFormatter(versionNames: VersionNames) = JiraComponentVersionFormatter(versionNames)

    @Bean
    fun numericVersionFactory(versionNames: VersionNames) = NumericVersionFactory(versionNames)

    @Bean
    fun versionRangeFactory(versionNames: VersionNames) = VersionRangeFactory(versionNames)

    @Bean
    fun moduleByArtifactResolver(versionNames: VersionNames) =
        ModuleByArtifactResolver(versionNames)

    @Bean
    fun versionNames(configHelper: ConfigHelper) = VersionNames(
        configHelper.serviceBranch(),
        configHelper.service(),
        configHelper.minor()
    )
}

fun main(args: Array<String>) {
    SpringApplication.run(ComponentRegistryServiceApplication::class.java, *args)
}
