package org.octopusden.octopus.components.registry.cli.output

/**
 * Output format selected via `-o` / `--output`. JSON is the machine-readable default; TABLE is a
 * compact human view for list-shaped data.
 */
enum class OutputFormat {
    JSON,
    TABLE,
    ;

    companion object {
        /** Parses a `-o` value case-insensitively; throws [IllegalArgumentException] on unknown. */
        fun parse(value: String): OutputFormat =
            when (value.trim().lowercase()) {
                "json" -> JSON
                "table" -> TABLE
                else -> throw IllegalArgumentException("Unknown output format '$value' (expected: json, table)")
            }
    }
}
