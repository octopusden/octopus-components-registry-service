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
            // Truncate the RAW value first, then sanitize. Order matters: if we
            // sanitize first and then `take(N)`, a `\xNN` escape can be split
            // mid-sequence at the cap boundary, leaving a dangling `\x` or
            // `\x0` in the output. Also drop a lone high surrogate at the
            // boundary if `take` split a UTF-16 surrogate pair.
            //
            // (PR #248 → final-review Opus P1-2: truncate-before-sanitize.)
            val truncatedRaw = truncateRaw(source, MAX_ERROR_ECHO_CHARS)
            val sanitized = sanitizeForEcho(truncatedRaw)
            val ellipsis = if (source.length > truncatedRaw.length) "…" else ""
            "Invalid component source '$sanitized$ellipsis'; allowed values: ${ALLOWED_SOURCES.joinToString(", ")}"
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
         * Printable characters (including Latin-1 0xA0..0xFF and astral-plane
         * code points represented as well-formed UTF-16 surrogate pairs) are
         * preserved verbatim.
         *
         * Callers MUST truncate before calling this (see [truncateRaw]); doing
         * the opposite would let `.take(N)` split a `\xNN` escape mid-sequence.
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

        /**
         * Caps `value` at `maxChars` UTF-16 code units. If the cut point falls
         * between the high and low halves of a UTF-16 surrogate pair, drops the
         * lone high surrogate so Jackson (or any UTF-8 serializer) never sees
         * malformed input.
         */
        internal fun truncateRaw(value: String, maxChars: Int): String {
            if (value.length <= maxChars) return value
            val raw = value.substring(0, maxChars)
            return if (raw.isNotEmpty() && raw.last().isHighSurrogate()) {
                raw.dropLast(1)
            } else {
                raw
            }
        }
    }
}
