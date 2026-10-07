package org.octopusden.octopus.components.registry.server.util

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * One configured entry's flattened keys, dumped back to YAML under its id for the administrator
 * read (Decision 11), so a failed entry shows what was read. Spring flattens the result to the
 * same keys again; values stay the strings Spring read.
 */
object EntryYaml {
    private val SEGMENT = Regex("""([^.\[]+)|\[(\d+)]""")

    private val yaml =
        Yaml(
            DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                indent = 2
            },
        )

    fun dump(
        id: String,
        keys: Map<String, String>,
    ): String {
        val scalar = keys[""]
        val value: Any = if (scalar != null && keys.size == 1) scalar else nest(keys.filterKeys { it.isNotEmpty() })
        return yaml.dump(mapOf(id to value))
    }

    private fun nest(keys: Map<String, String>): Any {
        val root = Node()
        keys.forEach { (key, value) ->
            var node = root
            SEGMENT.findAll(key).forEach { segment ->
                val index = segment.groupValues[2]
                if (index.isNotEmpty()) node.list = true
                node = node.children.getOrPut(index.ifEmpty { segment.groupValues[1] }) { Node() }
            }
            node.value = value
        }
        return root.toValue()
    }

    private class Node {
        val children = linkedMapOf<String, Node>()
        var value: String? = null
        var list = false

        fun toValue(): Any =
            when {
                children.isEmpty() -> value.orEmpty()
                list -> children.entries.sortedBy { it.key.toInt() }.map { it.value.toValue() }
                else -> children.mapValues { it.value.toValue() }
            }
    }
}
