package org.octopusden.octopus.components.registry.server.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.octopusden.octopus.components.registry.core.dto.Component
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration

/**
 * Sets `testComponent` on the v1/v2/v3 `Component` DTOs (SYS-099), following the component's source
 * like the routing resolver does:
 * - DB-sourced: the DB flag — membership in the cached key set of flagged components;
 * - Git-sourced, and every component in `no-db` mode: the legacy `test-component` label of the
 *   resolved model (the DSL has no flag), with no DB read for the flag.
 *
 * The key set is cached for [TTL] and evicted after a v4 write that changes the flag or renames a
 * flagged component commits ([evictAfterCommit]); other pods see such a change within [TTL].
 */
@Service
class TestComponentFlags(
    componentRepositoryProvider: ObjectProvider<ComponentRepository>,
    sourceRegistryProvider: ObjectProvider<ComponentSourceRegistry>,
    private val componentRegistryResolver: ComponentRegistryResolver,
    private val properties: ComponentsRegistryProperties,
) {
    private val componentRepository: ComponentRepository? by lazy { componentRepositoryProvider.getIfAvailable() }
    private val sourceRegistry: ComponentSourceRegistry? by lazy { sourceRegistryProvider.getIfAvailable() }

    private val flaggedKeys: Cache<Unit, Set<String>> = Caffeine.newBuilder().expireAfterWrite(TTL).build()

    fun mark(components: Collection<Component>) {
        if (components.isEmpty()) return
        val sourceOf = sourceLookup(components)
        components.forEach { component ->
            component.testComponent =
                if (sourceOf(component.id) == DB_SOURCE) {
                    component.id in flaggedKeys()
                } else {
                    TEST_COMPONENT_LABEL in labelsOf(component)
                }
        }
    }

    /** Drops the cached key set once the current transaction commits (immediately when there is none). */
    fun evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = flaggedKeys.invalidateAll()
                },
            )
        } else {
            flaggedKeys.invalidateAll()
        }
    }

    private fun flaggedKeys(): Set<String> = flaggedKeys.get(Unit) { componentRepository?.findTestComponentKeys()?.toSet() ?: emptySet() }

    /** One source lookup per component for a single DTO; one bulk read for a list. */
    private fun sourceLookup(components: Collection<Component>): (String) -> String {
        val registry = sourceRegistry ?: return { GIT_SOURCE }
        if (components.size == 1) return { registry.getSource(it) }
        val db = registry.getDbComponentNames()
        val git = registry.getGitComponentNames()
        return { key ->
            when (key) {
                in db -> DB_SOURCE
                in git -> GIT_SOURCE
                else -> properties.defaultSource
            }
        }
    }

    /** The DTO's labels, or — for DTOs built without them (find-by-artifact) — the resolved model's. */
    private fun labelsOf(component: Component): Set<String> =
        component.labels.ifEmpty {
            componentRegistryResolver
                .getComponentById(component.id)
                ?.let { (it.componentLevelConfiguration ?: it.moduleConfigurations.firstOrNull())?.labels }
                ?: emptySet()
        }

    companion object {
        const val TEST_COMPONENT_LABEL = "test-component"
        val TTL: Duration = Duration.ofSeconds(30)
        private const val DB_SOURCE = "db"
        private const val GIT_SOURCE = "git"
    }
}
