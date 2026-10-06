package org.octopusden.octopus.components.registry.server.profile

/**
 * Turns the flattened properties under `components-registry.component-profiles` into profiles,
 * one [ProfileEntry] per configured id, and configuration-level problems.
 *
 * Input keys are relative to that prefix (`solution.classification.external`,
 * `solution.rules.artifactIds[0].groupPattern.pattern`) and values are the strings the
 * environment resolves. Every problem is collected, each prefixed with the full key it concerns.
 * A template entry is failed without further checks. Pure: no Spring, no IO.
 */
object ComponentProfileParser {
    private val ID = Regex("[a-z0-9-]+")
    private val BOOLEANS = listOf("true", "false")
    private val PROFILE_KEYS =
        setOf("kind", "title", "description", "order", "classification.external", "classification.explicit", "classification.solution")
    private val REQUIRED_KEYS = listOf("kind", "title", "description", "order", "classification.external", "classification.explicit")
    private const val RULES = "rules."

    fun parse(properties: Map<String, String>): ProfileParseResult {
        val parsed =
            properties.entries
                .groupBy({ it.key.substringBefore('.') }, { it.key.substringAfter('.', "") to it.value })
                .map { (id, keyValues) -> EntryParser(id, keyValues.toMap()).parse() }
        val problems =
            if (parsed.none { it.first.kind == REGULAR_KIND }) listOf("at least one regular profile is required") else emptyList()
        return ProfileParseResult(
            profiles = parsed.mapNotNull { it.second }.sortedWith(compareBy({ it.order }, { it.id })),
            entries = parsed.map { it.first }.sortedBy { it.id },
            problems = problems,
        )
    }

    private class EntryParser(
        private val id: String,
        private val values: Map<String, String>,
    ) {
        private val problems = mutableListOf<String>()

        fun parse(): Pair<ProfileEntry, ComponentProfile?> {
            val kind = values["kind"]
            if (kind == TEMPLATE_KIND) {
                return ProfileEntry(id, kind, EntryStatus.FAILED, listOf("$id.kind: templates are not supported yet")) to null
            }
            checkStructure(kind)
            val profile = readProfile()
            return if (problems.isEmpty() && profile != null) {
                ProfileEntry(id, kind, EntryStatus.LIVE, emptyList()) to profile
            } else {
                ProfileEntry(id, kind, EntryStatus.FAILED, problems) to null
            }
        }

        private fun checkStructure(kind: String?) {
            if (!ID.matches(id)) problems += "$id: an id consists of lowercase letters, digits and '-'"
            values.keys
                .filter { it !in PROFILE_KEYS && !it.startsWith(RULES) }
                .forEach { problems += if (it.isEmpty()) "$id: must hold profile keys, not a value" else "$id.$it: unknown key" }
            REQUIRED_KEYS.filter { it !in values }.forEach { problems += "$id.$it: required" }
            if (kind != null && kind != REGULAR_KIND) problems += "$id.kind: '$kind' is not one of regular, template"
        }

        private fun readProfile(): ComponentProfile? {
            val title = text("title")
            val description = text("description")
            val order = order()
            val classification = classification()
            val rules = rules()
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

        private fun classification(): ProfileClassification? {
            val external = choice("classification.external", BOOLEANS)?.toBooleanStrict()
            val explicit = choice("classification.explicit", BOOLEANS + "ask")?.let { ExplicitChoice.valueOf(it.uppercase()) }
            val solution = choice("classification.solution", BOOLEANS)?.toBooleanStrict() ?: false
            val shippedExplicitly = external != false && explicit in setOf(null, ExplicitChoice.TRUE)
            if (solution && !shippedExplicitly) {
                problems += "$id.classification.solution: a solution profile needs external: true and explicit: true"
            }
            return ProfileClassification(external ?: return null, explicit ?: return null, solution)
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

        private fun rules(): List<FieldRule> {
            val byPath = mutableMapOf<String, MutableMap<String, String>>()
            values.filterKeys { it.startsWith(RULES) }.forEach { (key, value) ->
                val rest = key.removePrefix(RULES)
                if ('.' !in rest) {
                    problems += "$id.rules.$rest: a rule needs a pattern and a message"
                } else {
                    byPath.getOrPut(rest.substringBeforeLast('.')) { mutableMapOf() }[rest.substringAfterLast('.')] = value
                }
            }
            return byPath.mapNotNull { (path, rule) -> rule(path, rule) }
        }

        private fun rule(
            path: String,
            rule: Map<String, String>,
        ): FieldRule? {
            val prefix = "$id.rules.$path"
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
            return FieldRule(path, checkNotNull(pattern), checkNotNull(message))
        }
    }
}
