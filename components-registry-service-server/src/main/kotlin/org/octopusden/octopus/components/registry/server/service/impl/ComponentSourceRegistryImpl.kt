package org.octopusden.octopus.components.registry.server.service.impl

import com.github.benmanes.caffeine.cache.Caffeine
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.TimeUnit

@Service
class ComponentSourceRegistryImpl(
    private val componentSourceRepository: ComponentSourceRepository,
    private val properties: ComponentsRegistryProperties,
) : ComponentSourceRegistry {
    private val sourceCache =
        Caffeine
            .newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build<String, String>()

    // isDbComponent / isGitComponent check the component_source row literally and
    // return false when the row is missing. Migration code (ImportServiceImpl) uses
    // these to decide whether a component still needs to be migrated.
    override fun isDbComponent(name: String): Boolean =
        componentSourceRepository.findById(name).map { it.source == "db" }.orElse(false)

    override fun isGitComponent(name: String): Boolean =
        componentSourceRepository.findById(name).map { it.source == "git" }.orElse(false)

    // getSource returns the effective source for routing, applying the configured
    // default when no row exists. Used by ComponentRoutingResolver so that, once
    // everything is migrated (ft-db), unknown/renamed-away names route to the DB
    // resolver and don't leak DSL ghosts via the git resolver.
    override fun getSource(name: String): String =
        sourceCache.get(name) { key ->
            componentSourceRepository
                .findById(key)
                .map { it.source }
                .orElse(properties.defaultSource)
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
