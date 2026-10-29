package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.components.registry.server.model.TemplateExpression.Filter
import org.octopusden.octopus.components.registry.server.model.TemplateExpression.Part

class TemplateExpressionParserTest {
    private fun parse(text: String) = TemplateExpressionParser.parse(text).getOrThrow()

    @Test
    @DisplayName("Decision 3: the design example's name splits into parameters with their filters and the literal between them")
    fun designExampleName() {
        val expression = parse("{{ CLIENT_CODE | lower }}-plugin-{{ PLUGIN_CODE | lower }}")

        assertEquals(
            listOf(
                Part.Parameter("CLIENT_CODE", Filter.LOWER),
                Part.Literal("-plugin-"),
                Part.Parameter("PLUGIN_CODE", Filter.LOWER),
            ),
            expression.parts,
        )
        assertEquals(setOf("CLIENT_CODE", "PLUGIN_CODE"), expression.parameters)
    }

    @Test
    @DisplayName("spaces inside the braces are optional")
    fun spacesOptional() {
        assertEquals(parse("{{ PLUGIN_NAME | upper }} for"), parse("{{PLUGIN_NAME|upper}} for"))
    }

    @Test
    @DisplayName("text without expressions is one literal, kept as written")
    fun literalKept() {
        assertEquals(listOf(Part.Literal("  ssh://git@git.example.com/a b ")), parse("  ssh://git@git.example.com/a b ").parts)
    }

    @Test
    @DisplayName("a parameter alone, without a filter, is a whole value")
    fun wholeValue() {
        assertEquals("COMPONENT_OWNER", parse("{{ COMPONENT_OWNER }}").wholeParameter)
        assertEquals(null, parse("{{ COMPONENT_OWNER | lower }}").wholeParameter)
        assertEquals(null, parse("x{{ COMPONENT_OWNER }}").wholeParameter)
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = [
            "{{ CLIENT_CODE | lower }-plugin",
            "acme-{{ CLIENT_CODE",
            "{% if REGION %}eu-{% endif %}{{ CLIENT_CODE | lower }}",
            "{{ CLIENT_CODE }}{# owner #}",
            "{{ A ~ B }}",
            "{{ 'x' }}",
            "{{ }}",
            "{{ client_code }}",
            "{{ CLIENT_CODE | capitalize }}",
            "{{ CLIENT_CODE | lower | upper }}",
            "{{ CLIENT_CODE | }}",
        ],
    )
    @DisplayName("Decision 3: a construct outside the subset is rejected with a reason")
    fun outsideSubsetRejected(text: String) {
        val failure = TemplateExpressionParser.parse(text).exceptionOrNull()

        requireNotNull(failure?.message) { "'$text' must be rejected with a message" }
    }
}
