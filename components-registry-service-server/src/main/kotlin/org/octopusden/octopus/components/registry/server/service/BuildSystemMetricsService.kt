package org.octopusden.octopus.components.registry.server.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.springframework.stereotype.Service
import java.util.EnumMap

/**
 * Service for collecting metrics about components count by build system
 */
@Service
class BuildSystemMetricsService(
    meterRegistry: MeterRegistry,
    private val componentRegistryResolver: ComponentRegistryResolver
) {

    private var buildSystem = EnumMap<BuildSystem, Int>(BuildSystem::class.java)

    init {
        BuildSystem.values().forEach { buildSystem ->
            Gauge.builder("build.system.count.${buildSystem.name}") {
                getComponentCountForBuildSystem(buildSystem)
            }
                .description("Number of components for build system: $buildSystem")
                .tags("buildSystem", buildSystem.name)
                .register(meterRegistry)
        }
    }

    /**
     * Get component count for build system
     */
    private fun getComponentCountForBuildSystem(buildSystem: BuildSystem) = this.buildSystem[buildSystem] ?: 0

    /**
     * Update metrics
     */
    fun updateMetrics() {
        val componentCounts = componentRegistryResolver.getComponentsCountByBuildSystem()

        BuildSystem.values().forEach { buildSystem ->
            this.buildSystem[buildSystem] = componentCounts[buildSystem] ?: 0
        }
    }

}