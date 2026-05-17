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
        // Normalize THEN whitelist: `findBySource("db")` is case-sensitive and
        // `ComponentRoutingResolver.resolverFor` checks `== "db"` exactly, so any
        // casing/whitespace variant would route the component to gitResolver and
        // silently exclude it from getDbComponentNames() → 404 blackhole.
        val normalized = source.trim().lowercase()
        require(normalized in ALLOWED_SOURCES) {
            // Truncate echoed value to avoid log-injection / oversized payload landing
            // verbatim in ControllerExceptionHandler's HTTP response body and the WARN
            // log line (PR #247 Opus review P2-A). Hard ceiling of MAX_ERROR_ECHO_CHARS.
            val echo = source.take(MAX_ERROR_ECHO_CHARS)
            val ellipsis = if (source.length > MAX_ERROR_ECHO_CHARS) "…" else ""
            "Invalid component source '$echo$ellipsis'; allowed values: ${ALLOWED_SOURCES.joinToString(", ")}"
        }
        val entity =
            componentSourceRepository.findById(name).orElse(
                ComponentSourceEntity(componentKey = name),
            )
        entity.source = normalized
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

    companion object {
        private val ALLOWED_SOURCES = setOf("git", "db")
        private const val MAX_ERROR_ECHO_CHARS = 80
    }
}
