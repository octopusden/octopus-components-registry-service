package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.model.ComponentProfile

/**
 * Reads the `rules` of a profile or template: keys relative to `rules.` (`name.pattern`,
 * `artifactIds[0].groupPattern.message`), every problem prefixed with [prefix] and the key.
 * A rule with any problem is left out.
 */
internal object FieldRuleParser {
    fun parse(
        prefix: String,
        values: Map<String, String>,
        problems: MutableList<String>,
    ): List<ComponentProfile.FieldRule> {
        val byPath = linkedMapOf<String, MutableMap<String, String>>()
        values.forEach { (key, value) ->
            if ('.' !in key) {
                problems += "$prefix.$key: a rule needs a pattern and a message"
            } else {
                byPath.getOrPut(key.substringBeforeLast('.')) { mutableMapOf() }[key.substringAfterLast('.')] = value
            }
        }
        return byPath.mapNotNull { (path, rule) -> rule("$prefix.$path", path, rule, problems) }
    }

    private fun rule(
        prefix: String,
        path: String,
        rule: Map<String, String>,
        problems: MutableList<String>,
    ): ComponentProfile.FieldRule? {
        val before = problems.size
        if (path !in CreateRequestPaths.PATHS) problems += "$prefix: not a create-request path a rule may name"
        rule.keys.filter { it != "pattern" && it != "message" }.forEach { problems += "$prefix.$it: unknown key" }
        val pattern = rule["pattern"]
        val message = rule["message"]
        if (pattern == null) {
            problems += "$prefix.pattern: required"
        } else if (runCatching { Regex(pattern) }.isFailure) {
            problems += "$prefix.pattern: '$pattern' is not a valid regular expression"
        }
        when {
            message == null -> problems += "$prefix.message: required"
            message.isBlank() -> problems += "$prefix.message: must not be blank"
        }
        if (problems.size != before) return null
        return ComponentProfile.FieldRule(path, checkNotNull(pattern), checkNotNull(message))
    }
}
