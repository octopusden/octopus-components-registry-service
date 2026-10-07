package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.ProfileLoad
import org.octopusden.octopus.components.registry.server.template.TemplateParser

/**
 * Turns the flattened properties under `components-registry.component-profiles` into profiles,
 * one [ProfileLoad.Entry] per configured id, and configuration-level problems.
 *
 * Input keys are relative to that prefix (`solution.classification.external`,
 * `solution.rules.artifactIds[0].groupPattern.pattern`) and values are the strings the
 * environment resolves. Every problem is collected, each prefixed with the full key it concerns.
 * A `template` entry is handed to [TemplateParser]. Pure: no Spring, no IO.
 */
object ComponentProfileParser {
    private val ID = Regex("[a-z0-9-]+")
    private val BOOLEANS = listOf("true", "false")
    private const val EXTERNAL = "classification.external"
    private const val EXPLICIT = "classification.explicit"
    private const val SOLUTION = "classification.solution"
    private val REQUIRED_KEYS = listOf("kind", "title", "description", "order", EXTERNAL, EXPLICIT)
    private val PROFILE_KEYS = REQUIRED_KEYS.toSet() + SOLUTION

    /**
     * [defaults] are the create-request values `component-defaults` supplies, by template field
     * path; a template's required-field check counts them (Decision 4).
     */
    fun parse(
        properties: Map<String, String>,
        defaults: Map<String, String> = emptyMap(),
    ): ProfileLoad {
        val byId =
            properties.entries
                .groupBy({ it.key.substringBefore('.') }, { it.key.substringAfter('.', "") to it.value })
                .mapValues { (_, keyValues) -> keyValues.toMap() }
        val templates = byId.filterValues { it["kind"] == ComponentProfile.TEMPLATE_KIND }.map { (id, values) ->
            TemplateParser(id, values, defaults).parse()
        }
        val parsed = byId.filterValues { it["kind"] != ComponentProfile.TEMPLATE_KIND }.map { (id, values) -> EntryParser(id, values).parse() }
        val problems =
            if (parsed.none { it.first.kind == ComponentProfile.REGULAR_KIND }) {
                listOf(
                    "at least one regular profile is required",
                )
            } else {
                emptyList()
            }
        return ProfileLoad(
            profiles = parsed.mapNotNull { it.second }.sortedWith(compareBy({ it.order }, { it.id })),
            entries = (parsed.map { it.first } + templates.map { it.first }).sortedBy { it.id },
            problems = problems,
            templates = templates.mapNotNull { it.second }.sortedWith(compareBy({ it.order }, { it.id })),
        )
    }

    private class EntryParser(
        private val id: String,
        private val values: Map<String, String>,
    ) {
        private val problems = mutableListOf<String>()

        fun parse(): Pair<ProfileLoad.Entry, ComponentProfile?> {
            val kind = values["kind"]
            checkStructure(kind)
            val profile = readProfile()
            return if (problems.isEmpty() && profile != null) {
                ProfileLoad.Entry(id, kind, ProfileLoad.Entry.Status.LIVE, emptyList()) to profile
            } else {
                ProfileLoad.Entry(id, kind, ProfileLoad.Entry.Status.FAILED, problems) to null
            }
        }

        private fun checkStructure(kind: String?) {
            if (!ID.matches(id)) problems += "$id: an id consists of lowercase letters, digits and '-'"
            values.keys
                .filter { it !in PROFILE_KEYS && !it.startsWith(RULE_PREFIX) }
                .forEach { problems += if (it.isEmpty()) "$id: must hold profile keys, not a value" else "$id.$it: unknown key" }
            REQUIRED_KEYS.filter { it !in values }.forEach { problems += "$id.$it: required" }
            if (kind != null && kind != ComponentProfile.REGULAR_KIND) problems += "$id.kind: '$kind' is not one of regular, template"
        }

        private fun readProfile(): ComponentProfile? {
            val title = text("title")
            val description = text("description")
            val order = order()
            val classification = classification()
            val rules = FieldRuleParser.parse("$id.rules", values.ruleKeys(), problems)
            return ComponentProfile(
                id = id,
                title = title ?: return null,
                description = description ?: return null,
                order = order ?: return null,
                classification = classification ?: return null,
                rules = rules,
            )
        }

        private fun order(): Int? {
            val value = values["order"] ?: return null
            return value.toIntOrNull().also { if (it == null) problems += "$id.order: '$value' is not a whole number" }
        }

        private fun classification(): ComponentProfile.Classification? {
            val external = choice(EXTERNAL, BOOLEANS)?.toBooleanStrict()
            val explicit = choice(EXPLICIT, BOOLEANS + "ask")?.let { ComponentProfile.Explicit.valueOf(it.uppercase()) }
            val solution = choice(SOLUTION, BOOLEANS)?.toBooleanStrict() ?: false
            val shippedExplicitly = external != false && explicit in setOf(null, ComponentProfile.Explicit.TRUE)
            if (solution && !shippedExplicitly) {
                problems += "$id.classification.solution: a solution profile needs external: true and explicit: true"
            }
            return ComponentProfile.Classification(external ?: return null, explicit ?: return null, solution)
        }

        private fun text(key: String): String? {
            val value = values[key] ?: return null
            if (value.isBlank()) problems += "$id.$key: must not be blank"
            return value.takeIf { it.isNotBlank() }
        }

        private fun choice(
            key: String,
            allowed: List<String>,
        ): String? {
            val value = values[key] ?: return null
            if (value !in allowed) problems += "$id.$key: '$value' is not one of ${allowed.joinToString()}"
            return value.takeIf { it in allowed }
        }
    }
}

private const val RULE_PREFIX = "rules."

internal fun Map<String, String>.ruleKeys(): Map<String, String> =
    filterKeys { it.startsWith(RULE_PREFIX) }.mapKeys { it.key.removePrefix(RULE_PREFIX) }
