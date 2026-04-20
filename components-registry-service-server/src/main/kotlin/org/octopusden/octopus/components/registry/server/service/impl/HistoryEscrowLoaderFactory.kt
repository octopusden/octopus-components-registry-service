package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.escrow.config.ConfigHelper
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.releng.versions.VersionNames
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Builds a fresh [EscrowConfigurationLoader] pointed at a history-checkout
 * directory. Mirrors the production bootstrap wiring in
 * [org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication]
 * so the history loader parses the same config surface as the live app,
 * just out of an alternate clone.
 */
@Component
class HistoryEscrowLoaderFactory(
    private val configHelper: ConfigHelper,
    private val versionNames: VersionNames,
    private val componentsRegistryProperties: ComponentsRegistryProperties,
    private val resourceLoader: ResourceLoader,
) {
    /**
     * @param historyWorkDir root of the alternate clone (e.g. `${workDir}-history`).
     */
    fun build(historyWorkDir: Path): EscrowConfigurationLoader {
        val historyGroovyPath = resolveHistoryGroovyPath(historyWorkDir)
        val moduleConfigUrl =
            "file://" +
                historyGroovyPath.toAbsolutePath().toString() +
                "/" +
                componentsRegistryProperties.mainGroovyFile
        val resource = resourceLoader.getResource(moduleConfigUrl)
        val url = resource.url
        val configLoader =
            ConfigLoader(
                ComponentRegistryInfo.createFromURL(url),
                versionNames,
                configHelper.productTypes(),
            )
        return EscrowConfigurationLoader(
            configLoader,
            configHelper.supportedGroupIds(),
            configHelper.supportedSystems(),
            versionNames,
            configHelper.copyrightPath(),
        )
    }

    /**
     * Preserve the `groovyPath`/`workDir` relationship inside the history clone:
     * in prod `groovyPath = ${workDir}/src/main/resources`, in tests they are
     * equal. The relative offset is applied on top of [historyWorkDir].
     */
    private fun resolveHistoryGroovyPath(historyWorkDir: Path): Path {
        val liveWorkDir = Paths.get(componentsRegistryProperties.workDir).toAbsolutePath().normalize()
        val liveGroovyPath = Paths.get(componentsRegistryProperties.groovyPath).toAbsolutePath().normalize()
        val relative =
            if (liveGroovyPath.startsWith(liveWorkDir)) {
                liveWorkDir.relativize(liveGroovyPath)
            } else {
                // Groovy path outside workDir — fall back to flat layout for history
                Paths.get("")
            }
        return historyWorkDir.resolve(relative.toString())
    }
}
