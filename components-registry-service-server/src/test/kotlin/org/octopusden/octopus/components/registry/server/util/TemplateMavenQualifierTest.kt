package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.octopusden.octopus.components.registry.server.support.EXAMPLE_DEFAULTS
import org.octopusden.octopus.components.registry.server.support.TEMPLATE_ID
import org.octopusden.octopus.components.registry.server.support.at
import org.octopusden.octopus.components.registry.server.support.designExampleProperties
import org.octopusden.octopus.components.registry.server.support.exampleTemplate
import org.octopusden.octopus.components.registry.server.support.flatten

class TemplateMavenQualifierTest {
    private fun problems(maven: Map<String, Any>): List<String> {
        val template = exampleTemplate().apply { at("fields.baseConfiguration")["mavenArtifacts"] = listOf(maven) }
        return ComponentProfileParser
            .parse(designExampleProperties() + flatten(template, TEMPLATE_ID), EXAMPLE_DEFAULTS)
            .entries
            .single { it.id == TEMPLATE_ID }
            .problems
    }

    @ParameterizedTest(name = "{0} with only {1}")
    @CsvSource(
        "extension, ''",
        "extension, groupPattern",
        "extension, artifactPattern",
        "classifier, ''",
        "classifier, groupPattern",
        "classifier, artifactPattern",
    )
    @DisplayName("a Maven extension or classifier without both the groupPattern and artifactPattern is a problem naming it")
    fun qualifierWithoutCoordinate(
        qualifier: String,
        coordinate: String,
    ) {
        val maven = linkedMapOf<String, Any>(qualifier to "tgz")
        if (coordinate.isNotEmpty()) maven[coordinate] = "org.example.plugins"

        val problems = problems(maven)

        val key = "$TEMPLATE_ID.fields.baseConfiguration.mavenArtifacts[0].$qualifier:"
        assertTrue(problems.any { it.startsWith(key) }, "expected a problem on '$key', got $problems")
    }

    @Test
    @DisplayName("a Maven extension and classifier with the full coordinate load live")
    fun qualifiersWithCoordinateAreLive() {
        val problems =
            problems(
                mapOf(
                    "groupPattern" to "org.example.plugins",
                    "artifactPattern" to "core",
                    "extension" to "tgz",
                    "classifier" to "dist",
                ),
            )

        assertEquals(emptyList<String>(), problems)
    }
}
