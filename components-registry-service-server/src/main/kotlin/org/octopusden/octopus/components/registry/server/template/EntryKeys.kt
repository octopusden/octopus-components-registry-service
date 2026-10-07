package org.octopusden.octopus.components.registry.server.template

/**
 * The flattened keys of one configuration entry, or of a section of it, and the problem list
 * every reader adds to. A problem is prefixed with [prefix] and the key, so it names the full key.
 */
internal class EntryKeys(
    private val prefix: String,
    val values: Map<String, String>,
    val problems: MutableList<String>,
) {
    operator fun get(key: String): String? = values[key]

    operator fun contains(key: String): Boolean = key in values

    fun problem(
        key: String?,
        message: String,
    ) {
        problems += if (key == null) "$prefix: $message" else "$prefix.$key: $message"
    }

    /** The keys under `section.`, relative to it. */
    fun section(section: String): EntryKeys =
        EntryKeys(
            "$prefix.$section",
            values.filterKeys { it.startsWith("$section.") }.mapKeys { it.key.removePrefix("$section.") },
            problems,
        )

    /** The names directly under `section.`, in configured order. */
    fun names(section: String): List<String> =
        values.keys
            .filter { it.startsWith("$section.") }
            .map { it.removePrefix("$section.").substringBefore('.') }
            .distinct()

    fun text(key: String): String? {
        val value = values[key] ?: return null
        if (value.isBlank()) problem(key, "must not be blank")
        return value.takeIf { it.isNotBlank() }
    }

    fun number(
        key: String,
        positive: Boolean,
    ): Int? {
        val value = values[key] ?: return null
        val number = value.toIntOrNull()?.takeIf { !positive || it > 0 }
        if (number == null) problem(key, "'$value' is not a whole number${if (positive) " above 0" else ""}")
        return number
    }

    fun bool(key: String): Boolean? {
        val value = values[key] ?: return null
        return value.toBooleanStrictOrNull().also { if (it == null) problem(key, "'$value' is not one of true, false") }
    }

    /** A YAML list (`key[0]`, `key[1]`, in index order), or a single `key` value as a one-item list. */
    fun list(key: String): List<String> =
        values[key]?.let { listOf(it) }
            ?: values
                .filterKeys { it.startsWith("$key[") }
                .entries
                .sortedBy {
                    it.key
                        .removePrefix("$key[")
                        .substringBefore(']')
                        .toIntOrNull() ?: Int.MAX_VALUE
                }.map { it.value }
}
