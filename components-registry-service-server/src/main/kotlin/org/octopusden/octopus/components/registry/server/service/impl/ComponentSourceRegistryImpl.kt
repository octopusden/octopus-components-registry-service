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
            // Sanitize THEN truncate the echoed value to avoid log-injection +
            // oversized payload landing verbatim in ControllerExceptionHandler's
            // HTTP response body and the WARN log line. Sanitization replaces any
            // CR/LF/TAB/other ISO C0/C1 control character with an escaped \xNN
            // form (so `bad\nWARN forged` cannot inject a fake log line),
            // truncation caps the result at MAX_ERROR_ECHO_CHARS.
            // (PR #248 follow-up to PR #247 Opus review P2-A.)
            val sanitized = sanitizeForEcho(source)
            val echo = sanitized.take(MAX_ERROR_ECHO_CHARS)
            val ellipsis = if (sanitized.length > MAX_ERROR_ECHO_CHARS) "…" else ""
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

        /**
         * Replaces every ISO C0/C1 control character (0x00..0x1F and 0x7F..0x9F)
         * with an escaped `\xNN` literal so a value like `bad\nWARN forged` can
         * never inject a fake log line nor break the JSON-rendered HTTP body.
         * Printable characters are preserved verbatim; the result is then safely
         * truncated by the caller without re-introducing partial escapes.
         */
        internal fun sanitizeForEcho(value: String): String =
            buildString(value.length) {
                for (ch in value) {
                    val code = ch.code
                    if (code in 0x00..0x1F || code in 0x7F..0x9F) {
                        append("\\x").append(code.toString(16).uppercase().padStart(2, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
    }
}
