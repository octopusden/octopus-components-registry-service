package org.octopusden.octopus.components.registry.server.model

/**
 * One template field value as literal text and parameter references (Decision 3), as
 * [org.octopusden.octopus.components.registry.server.util.TemplateExpressionParser] reads it.
 */
data class TemplateExpression(
    val parts: List<Part>,
) {
    val parameters: Set<String>
        get() = parts.filterIsInstance<Part.Parameter>().mapTo(linkedSetOf()) { it.name }

    /** The parameter name when the value is exactly `{{ NAME }}`, with no filter and no text around it. */
    val wholeParameter: String?
        get() = (parts.singleOrNull() as? Part.Parameter)?.takeIf { it.filter == null }?.name

    sealed interface Part {
        data class Literal(
            val text: String,
        ) : Part

        data class Parameter(
            val name: String,
            val filter: Filter? = null,
        ) : Part
    }

    enum class Filter {
        LOWER,
        UPPER,

        /** Splits on `-`, `_` and whitespace, capitalizes each non-empty word, joins with a single space. */
        TITLE,

        /** Lowercases, replaces each run of non-alphanumeric ASCII characters with `-`, trims the result. */
        KEBAB,
        ;

        fun transform(value: String): String =
            when (this) {
                LOWER -> value.lowercase()
                UPPER -> value.uppercase()
                TITLE ->
                    value
                        .split(TITLE_SEPARATOR)
                        .filter { it.isNotEmpty() }
                        .joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) }
                KEBAB -> value.lowercase().replace(KEBAB_SEPARATOR, "-").trim('-')
            }

        private companion object {
            val TITLE_SEPARATOR = Regex("""[-_\s]+""")

            // shortcut: ASCII alphanumerics only; a non-ASCII value (such as "Café") becomes mostly
            // dashes. Widen to `[^\p{L}\p{N}]+` if a template field needs `kebab` on non-ASCII text.
            val KEBAB_SEPARATOR = Regex("[^a-z0-9]+")
        }
    }
}
