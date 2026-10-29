package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.ComponentSourceEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.springframework.stereotype.Service
import java.time.Instant

@ConditionalOnDatabaseEnabled
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
        // Normalize THEN whitelist: `findComponentKeysBySource("db")` is case-sensitive and
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

    override fun getDbComponentNames(): Set<String> = componentSourceRepository.findComponentKeysBySource("db").toSet()

    override fun getGitComponentNames(): Set<String> = componentSourceRepository.findComponentKeysBySource("git").toSet()

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
         * Caps `value` at `maxChars` UTF-16 code units, then sanitizes the
         * boundary against malformed UTF-16:
         *
         *  - If the cut split a well-formed surrogate pair (last char is a high
         *    surrogate but its low partner was at `maxChars`, beyond the cut),
         *    drop the lone high surrogate.
         *  - If the input itself was malformed and the cut landed on a lone
         *    low surrogate (no high partner at `maxChars - 2`), drop the lone
         *    low surrogate.
         *
         * Either way, Jackson (or any UTF-8 serializer) never sees a half-pair.
         */
        internal fun truncateRaw(
            value: String,
            maxChars: Int,
        ): String {
            if (value.length <= maxChars) return value
            val raw = value.substring(0, maxChars)
            if (raw.isEmpty()) return raw
            val last = raw.last()
            return when {
                last.isHighSurrogate() -> raw.dropLast(1)
                last.isLowSurrogate() && (raw.length < 2 || !raw[raw.length - 2].isHighSurrogate()) ->
                    raw.dropLast(1)
                else -> raw
            }
        }
    }
}
