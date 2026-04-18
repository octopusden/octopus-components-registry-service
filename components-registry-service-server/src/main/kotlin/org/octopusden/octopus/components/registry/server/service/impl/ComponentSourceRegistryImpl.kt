package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ComponentSourceRegistryImpl(
    private val componentSourceRepository: ComponentSourceRepository,
    private val properties: ComponentsRegistryProperties,
) : ComponentSourceRegistry {
    // All reads go through the repository. The earlier Caffeine cache was per-JVM,
    // so a `component_source` rewrite on pod A was invisible to pod B until the
    // 5-minute write-expiry elapsed — that re-introduced the ghost SYS-029
    // eliminated on a single-JVM setup (SYS-032). `component_source.name` is the
    // primary key and backed by the default unique index, so `findById` is a
    // single PK seek — sub-millisecond on H2 and indexed disk reads on Postgres.
    // If the hot path becomes a bottleneck the route to scale is a Hibernate L2
    // cache with cluster-aware invalidation, not a per-JVM Caffeine cache.

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
                ComponentSourceEntity(componentName = name),
            )
        entity.source = source
        entity.migratedAt = Instant.now()
        componentSourceRepository.save(entity)
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
    }

    override fun getDbComponentNames(): Set<String> = componentSourceRepository.findBySource("db").map { it.componentName }.toSet()

    override fun getGitComponentNames(): Set<String> = componentSourceRepository.findBySource("git").map { it.componentName }.toSet()
}
