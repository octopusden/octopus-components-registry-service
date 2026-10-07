package org.octopusden.octopus.components.registry.server.template

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.util.FieldRuleParser
import org.octopusden.octopus.components.registry.server.model.ProfileLoad
import org.octopusden.octopus.components.registry.server.util.ruleKeys
import org.octopusden.octopus.components.registry.server.template.TemplateFieldChecker.Companion.FIELDS

/**
 * Parses one `kind: template` entry and applies every load check of the spec (Decision 4),
 * collecting every problem, each prefixed with the full key it concerns.
 *
 * [values] are the entry's flattened keys, relative to its id. [defaults] are the values
 * `component-defaults` supplies by field path; a required field they fill counts as produced.
 * Fixed labels, fixed people and uniqueness are deliberately not checked here: they depend on
 * data that changes at runtime, and the dry run checks them. Pure: no Spring, no IO.
 */
internal class TemplateParser(
    private val id: String,
    values: Map<String, String>,
    private val defaults: Map<String, String>,
) {
    private val keys = EntryKeys(id, values, mutableListOf())

    fun parse(): Pair<ProfileLoad.Entry, ComponentTemplate?> {
        checkKeys()
        val title = keys.text("title")
        val description = keys.text("description")
        val order = keys.number("order", positive = false)
        val version = keys.number("version", positive = true)
        val classification = classification()
        val declared = keys.names(PARAMETERS)
        val parameters = declared.mapNotNull { TemplateParameterParser(it, keys.section("$PARAMETERS.$it")).parse() }
        val fields = fields()
        TemplateFieldChecker(parameters, declared.toSet(), keys).check(fields)
        checkUse(parameters, fields)
        val overridable = overridable(fields)
        val rules = FieldRuleParser.parse("$id.rules", keys.values.ruleKeys(), keys.problems)
        classification?.let { TemplateRequiredFields(parameters, fields, defaults, keys).check(it) }
        classification?.let { checkOwnRules(rules, fields, it) }
        if (keys.problems.isNotEmpty()) return ProfileLoad.Entry(id, KIND, ProfileLoad.Entry.Status.FAILED, keys.problems.toList()) to null
        val template =
            ComponentTemplate(
                id = id,
                title = checkNotNull(title),
                description = checkNotNull(description),
                order = checkNotNull(order),
                version = checkNotNull(version),
                classification = checkNotNull(classification),
                parameters = parameters,
                fields = fields,
                overridable = overridable,
                rules = rules,
            )
        return ProfileLoad.Entry(id, KIND, ProfileLoad.Entry.Status.LIVE, emptyList()) to template
    }

    private fun checkKeys() {
        keys.values.keys
            .filter { key -> key !in SCALAR_KEYS && SECTIONS.none { key.startsWith(it) } && !OVERRIDABLE_ITEM.matches(key) }
            .forEach { if (it.isEmpty()) keys.problem(null, "must hold template keys, not a value") else keys.problem(it, "unknown key") }
        REQUIRED_KEYS.filter { it !in keys }.forEach { keys.problem(it, "required") }
        if (keys.values.keys.none { it.startsWith(FIELDS) }) keys.problem("fields", "required")
    }

    private fun classification(): ComponentProfile.Classification? {
        val external = keys.bool(EXTERNAL)
        val explicit =
            when (keys[EXPLICIT]) {
                null -> null
                "ask" -> null.also { keys.problem(EXPLICIT, "'ask' is for regular profiles; a template is explicit true or false") }
                else -> keys.bool(EXPLICIT)
            }
        val solution = keys.bool(SOLUTION) ?: false
        if (solution && (external == false || explicit == false)) {
            keys.problem(SOLUTION, "a solution template needs external: true and explicit: true")
        }
        return ComponentProfile.Classification(
            external ?: return null,
            if (explicit ?: return null) ComponentProfile.Explicit.TRUE else ComponentProfile.Explicit.FALSE,
            solution,
        )
    }

    private fun fields(): Map<String, TemplateField> {
        val singles = linkedMapOf<String, TemplateExpression>()
        val items = linkedMapOf<String, MutableList<Pair<Int, TemplateExpression>>>()
        keys.values.filterKeys { it.startsWith(FIELDS) }.forEach { (key, value) ->
            val path = key.removePrefix(FIELDS)
            val item = LIST_ITEM.matchEntire(path)
            val listPath = item?.groupValues?.get(1)?.takeIf { TemplateFields.KINDS[it]?.list == true }
            val kind = TemplateFields.KINDS[path]
            val expression = { expression(key, value) }
            when {
                listPath != null -> expression()?.let { items.getOrPut(listPath) { mutableListOf() } += item.groupValues[2].toInt() to it }
                kind?.list == true -> expression()?.let { items.getOrPut(path) { mutableListOf() } += 0 to it }
                kind != null -> expression()?.let { singles[path] = it }
                else -> keys.problem(key, "not a create-request path a template may set")
            }
        }
        return singles.mapValues { (path, value) -> TemplateField.Single(path, TemplateFields.KINDS.getValue(path), value) } +
            items.mapValues { (path, indexed) ->
                TemplateField.Items(path, TemplateFields.KINDS.getValue(path), indexed.sortedBy { it.first }.map { it.second })
            }
    }

    private fun expression(
        key: String,
        value: String,
    ): TemplateExpression? =
        TemplateExpression
            .parse(value)
            .onFailure { keys.problem(key, it.message.orEmpty()) }
            .getOrNull()

    private fun checkUse(
        parameters: List<TemplateParameter>,
        fields: Map<String, TemplateField>,
    ) {
        val used = fields.values.flatMapTo(mutableSetOf()) { it.parameters }
        parameters.filter { it.name !in used }.forEach { keys.problem("$PARAMETERS.${it.name}", "no field uses it") }
    }

    private fun overridable(fields: Map<String, TemplateField>): List<String> =
        keys.values
            .filterKeys { OVERRIDABLE_ITEM.matches(it) }
            .entries
            .sortedBy {
                it.key
                    .removePrefix("overridable[")
                    .removeSuffix("]")
                    .toInt()
            }.onEach { (key, path) -> if (path !in fields) keys.problem(key, "'$path' is not set in fields") }
            .map { it.value }

    /**
     * A rule on a field built from parameters is checked by the dry run, on the rendered value. An
     * unset field is checked with the default the renderer would give it, or as empty.
     */
    private fun checkOwnRules(
        rules: List<ComponentProfile.FieldRule>,
        fields: Map<String, TemplateField>,
        classification: ComponentProfile.Classification,
    ) {
        val buildSystem =
            when (val field = fields[TemplateFields.BUILD_SYSTEM]) {
                null -> defaults[TemplateFields.BUILD_SYSTEM]
                is TemplateField.Single -> field.value.literal().takeIf { field.value.parameters.isEmpty() }
                is TemplateField.Items -> null
            }
        val applicable = ComponentDefaultsSeed.applicable(defaults, classification, buildSystem)
        rules.forEach { rule -> checkOwnRule(rule, fields, applicable) }
    }

    private fun checkOwnRule(
        rule: ComponentProfile.FieldRule,
        fields: Map<String, TemplateField>,
        defaults: Map<String, String>,
    ) {
        val field = fields[rule.path] as? TemplateField.Single
        val value =
            when {
                field == null -> defaults[rule.path].orEmpty()
                field.value.parameters.isEmpty() -> field.value.literal()
                else -> return
            }
        if (!rule.regex.matches(value)) keys.problem("rules.${rule.path}", "the value '$value' breaks the rule: ${rule.message}")
    }

    companion object {
        private const val KIND = "template"
        private const val EXTERNAL = "classification.external"
        private const val EXPLICIT = "classification.explicit"
        private const val SOLUTION = "classification.solution"
        private const val PARAMETERS = "parameters"
        private val REQUIRED_KEYS = listOf("title", "description", "order", "version", EXTERNAL, EXPLICIT)
        private val SCALAR_KEYS = setOf("kind", "title", "description", "order", "version", EXTERNAL, EXPLICIT, SOLUTION)
        private val SECTIONS = listOf("$PARAMETERS.", FIELDS, "rules.")
        private val OVERRIDABLE_ITEM = Regex("""overridable\[\d+]""")
        private val LIST_ITEM = Regex("""(.+)\[(\d+)]""")
    }
}
