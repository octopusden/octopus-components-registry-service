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
    }
}
