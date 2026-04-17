package org.octopusden.octopus.components.registry.server.service.impl

import com.github.benmanes.caffeine.cache.Caffeine
import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.TimeUnit

@Service
class ComponentSourceRegistryImpl(
    private val componentSourceRepository: ComponentSourceRepository,
) : ComponentSourceRegistry {
    private val sourceCache =
        Caffeine
            .newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build<String, String>()

    override fun isDbComponent(name: String): Boolean = getSource(name) == "db"

    override fun isGitComponent(name: String): Boolean = getSource(name) != "db"

    override fun getSource(name: String): String =
        sourceCache.get(name) { key ->
            componentSourceRepository
                .findById(key)
                .map { it.source }
                .orElse("git")
        }!!

    override fun setComponentSource(
        name: String,
        source: String,
    ) {
        val entity =
            componentSourceRepository.findById(name).orElse(
                ComponentSourceEntity(componentName = name),
            )
        entity.source = source
        entity.migratedAt = Instant.now()
        componentSourceRepository.save(entity)
        sourceCache.put(name, source)
    }

    override fun renameComponent(
        oldName: String,
        newName: String,
    ) {
        val existing = componentSourceRepository.findById(oldName).orElse(null) ?: return
        // component_source PK is the component name; replace the row atomically.
        componentSourceRepository.delete(existing)
        componentSourceRepository.flush()
        componentSourceRepository.save(
            ComponentSourceEntity(
                componentName = newName,
                source = existing.source,
                migratedAt = existing.migratedAt,
                migratedBy = existing.migratedBy,
            ),
        )
        sourceCache.invalidate(oldName)
        sourceCache.put(newName, existing.source)
    }

    override fun getDbComponentNames(): Set<String> = componentSourceRepository.findBySource("db").map { it.componentName }.toSet()

    override fun getGitComponentNames(): Set<String> = componentSourceRepository.findBySource("git").map { it.componentName }.toSet()
}
