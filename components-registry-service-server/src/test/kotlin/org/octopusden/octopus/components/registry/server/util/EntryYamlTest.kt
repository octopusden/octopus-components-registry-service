package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.template.exampleTemplate
import org.octopusden.octopus.components.registry.server.template.flatten
import org.yaml.snakeyaml.Yaml

class EntryYamlTest {
    private fun keysOf(entry: Map<String, Any>) = flatten(entry, "x").mapKeys { it.key.removePrefix("x.") }

    @Test
    @DisplayName("Decision 11: an entry's flattened keys dump back to the YAML they were read from, under its id")
    fun roundTrip() {
        val yaml = EntryYaml.dump("client-plugin", keysOf(exampleTemplate()))

        assertEquals(mapOf("client-plugin" to exampleTemplate()), Yaml().load<Map<String, Any>>(yaml))
    }

    @Test
    @DisplayName("list items come back in index order, however the keys arrive")
    fun listOrder() {
        val keys = linkedMapOf("labels[10]" to "c", "labels[2]" to "b", "labels[0]" to "a")

        assertEquals(mapOf("e" to mapOf("labels" to listOf("a", "b", "c"))), Yaml().load<Map<String, Any>>(EntryYaml.dump("e", keys)))
    }

    @Test
    @DisplayName("a rule path's dots and brackets nest as the configuration nests them")
    fun rulePath() {
        val keys = mapOf("rules.artifactIds[0].groupPattern.pattern" to "^org\\..*", "rules.artifactIds[0].groupPattern.message" to "m")

        assertEquals(
            mapOf(
                "e" to
                    mapOf(
                        "rules" to
                            mapOf("artifactIds" to listOf(mapOf("groupPattern" to mapOf("pattern" to "^org\\..*", "message" to "m")))),
                    ),
            ),
            Yaml().load<Map<String, Any>>(EntryYaml.dump("e", keys)),
        )
    }

    @Test
    @DisplayName("an entry configured as a plain value dumps as that value")
    fun scalarEntry() {
        assertEquals(mapOf("e" to "oops"), Yaml().load<Map<String, Any>>(EntryYaml.dump("e", mapOf("" to "oops"))))
    }
}
