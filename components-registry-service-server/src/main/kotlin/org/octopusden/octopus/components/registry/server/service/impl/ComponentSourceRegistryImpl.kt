package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.entity.v2.ComponentSourceEntity
import org.octopusden.octopus.components.registry.server.repository.v2.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ComponentSourceRegistryImpl(
    private val componentSourceRepository: ComponentSourceRepository,
    private val properties: ComponentsRegistryProperties,
) : ComponentSourceRegistry {
    override fun isDbComponent(name: String): Boolean = componentSourceRepository.findById(name).map { it.source == "db" }.orElse(false)

    override fun isGitComponent(name: String): Boolean = componentSourceRepository.findById(name).map { it.source == "git" }.orElse(false)

    override fun getSource(name: String): String =
        componentSourceRepository
            .findById(name)
            .map { it.source }
            .orElse(properties.defaultSource)

    override fun setComponentSource(
        name: String,
        source: String,
    ) {
        val entity =
            componentSourceRepository.findById(name).orElse(
                ComponentSourceEntity(componentKey = name),
            )
        entity.source = source
        entity.migratedAt = Instant.now()
        componentSourceRepository.save(entity)
    }

    override fun renameComponent(
        oldName: String,
        newName: String,
    ) {
        componentSourceRepository.renameComponentKey(oldName, newName)
    }

    override fun getDbComponentNames(): Set<String> = componentSourceRepository.findBySource("db").map { it.componentKey }.toSet()

    override fun getGitComponentNames(): Set<String> = componentSourceRepository.findBySource("git").map { it.componentKey }.toSet()
}
