package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.model.TemplateExpression

/**
 * Parses one template field value into a [TemplateExpression] (Decision 3). The syntax is a small
 * subset of Jinja: `{{ NAME }}`, `{{ NAME | lower }}`, `{{ NAME | upper }}`. It is parsed here
 * rather than by a Jinja engine so the parameters a field uses are known on load, which the load
 * checks and the dry run's attribution rely on. Any other Jinja construct is rejected.
 */
object TemplateExpressionParser {
    private const val OPEN = "{{"
    private const val CLOSE = "}}"
    private val REFERENCE = Regex("""\s*([A-Z][A-Z0-9_]*)\s*(?:\|\s*([a-z]+)\s*)?""")

    /** The parsed value, or a failure whose message says what is outside the subset. */
    fun parse(text: String): Result<TemplateExpression> =
        runCatching {
            require("{%" !in text) { "'{% … %}' tags are not supported; only {{ NAME }}, {{ NAME | lower }} and {{ NAME | upper }}" }
            require("{#" !in text) { "'{# … #}' comments are not supported" }
            TemplateExpression(parts(text))
        }

    private fun parts(text: String): List<TemplateExpression.Part> {
        val parts = mutableListOf<TemplateExpression.Part>()
        var position = 0
        while (position < text.length) {
            val open = text.indexOf(OPEN, position)
            if (open < 0) {
                parts += TemplateExpression.Part.Literal(text.substring(position))
                break
            }
            if (open > position) parts += TemplateExpression.Part.Literal(text.substring(position, open))
            val close = text.indexOf(CLOSE, open + OPEN.length)
            require(close >= 0) { "'{{' at position $open has no closing '}}'" }
            parts += reference(text.substring(open + OPEN.length, close))
            position = close + CLOSE.length
        }
        return parts
    }

    private fun reference(inner: String): TemplateExpression.Part.Parameter {
        val match =
            requireNotNull(REFERENCE.matchEntire(inner)) {
                "'{{$inner}}' is not a parameter name (upper-case letters, digits and '_') with an optional '| lower' or '| upper'"
            }
        val filter =
            match.groupValues[2].takeIf { it.isNotEmpty() }?.let { name ->
                requireNotNull(TemplateExpression.Filter.entries.find { it.name.lowercase() == name }) {
                    "'| $name' is not a supported filter; only 'lower' and 'upper' are"
                }
            }
        return TemplateExpression.Part.Parameter(match.groupValues[1], filter)
    }
}
