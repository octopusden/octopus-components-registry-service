package org.octopusden.octopus.components.registry.server.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for collecting metrics about components count by build system
 */
@Service
class BuildSystemMetricsService(
    meterRegistry: MeterRegistry,
    private val componentRegistryResolver: ComponentRegistryResolver
) {

    private val buildSystemMetrics = ConcurrentHashMap<BuildSystem, Int>()

    init {
        BuildSystem.values().forEach { buildSystem ->
            Gauge.builder("build.system.count") {
                buildSystemMetrics[buildSystem] ?: 0
            }
                .description("Number of components for build system: $buildSystem")
                .tag("buildSystem", buildSystem.name)
                .register(meterRegistry)
        }
    }

    /**
     * Update metrics
     */
    fun updateMetrics() {
        val componentCounts = componentRegistryResolver.getComponentsCountByBuildSystem()

        BuildSystem.values().forEach { buildSystem ->
            buildSystemMetrics[buildSystem] = componentCounts[buildSystem] ?: 0
        }
    }

}