package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.model.TemplateList
import org.octopusden.octopus.components.registry.server.model.TemplateParameter

/**
 * Parses one entry of a template's `parameters` and applies the spec's parameter checks.
 * Returns `null` when the parameter has any problem; whether a field uses it is checked by
 * [TemplateParser].
 */
internal class TemplateParameterParser(
    private val name: String,
    private val keys: EntryKeys,
) {
    fun parse(): TemplateParameter? {
        val before = keys.problems.size
        if (!NAME.matches(name)) keys.problem(null, "a parameter name is upper-case letters, digits and '_', starting with a letter")
        val type = keys["type"]
        checkKeys(type)
        val label = keys["label"]
        if (label == null) keys.problem("label", "required") else keys.text("label")
        val common = Common(label.orEmpty(), keys["hint"], keys.bool("required") ?: true)
        val parameter =
            when (type) {
                null -> null.also { keys.problem("type", "required") }
                TEXT -> text(common)
                SELECT -> select(common)
                CRS_LIST -> crsList(common)
                PERSON -> person(common)
                else -> null.also { keys.problem("type", "'$type' is not one of ${TYPE_KEYS.keys.joinToString()}") }
            }
        return parameter.takeIf { keys.problems.size == before }
    }

    private data class Common(
        val label: String,
        val hint: String?,
        val required: Boolean,
    )

    private fun checkKeys(type: String?) {
        val applicable = TYPE_KEYS[type].orEmpty()
        keys.values.keys.map { it.substringBefore('[') }.distinct().forEach { key ->
            when {
                key in COMMON_KEYS || key in applicable -> Unit
                TYPE_KEYS.values.none { key in it } -> keys.problem(key, "unknown key")
                type in TYPE_KEYS -> keys.problem(key, "does not apply to a $type parameter")
            }
        }
    }

    private fun default(multiple: Boolean): List<String> =
        keys.list(DEFAULT).also {
            if (!multiple && it.size > 1) keys.problem(DEFAULT, "several values for a single-value parameter")
        }

    private fun text(common: Common): TemplateParameter.Text {
        val pattern = keys[PATTERN]
        val regex =
            pattern?.let {
                runCatching { Regex(it) }.getOrNull().also { regex ->
                    if (regex == null) keys.problem(PATTERN, "'$pattern' is not a valid regular expression")
                }
            }
        val maxLength = keys.number(MAX_LENGTH, positive = true)
        val default = default(multiple = false).singleOrNull()
        if (default != null) {
            if (regex != null && !regex.matches(default)) keys.problem(DEFAULT, "'$default' does not match the pattern")
            if (maxLength != null && default.length > maxLength) keys.problem(DEFAULT, "'$default' is longer than $maxLength")
        }
        return TemplateParameter.Text(name, common.label, common.hint, common.required, pattern, keys["message"], maxLength, default)
    }

    private fun select(common: Common): TemplateParameter.Select {
        val multiple = keys.bool(MULTIPLE) ?: false
        val options = keys.list(OPTIONS)
        when {
            options.isEmpty() -> keys.problem(OPTIONS, "required, with at least one option")
            options.size != options.toSet().size -> keys.problem(OPTIONS, "repeats an option")
        }
        val maxSelection = keys.number(MAX_SELECTION, positive = true)
        if (maxSelection != null && !multiple) keys.problem(MAX_SELECTION, "applies only with multiple: true")
        val default = default(multiple)
        default.filter { it !in options }.forEach { keys.problem(DEFAULT, "'$it' is not one of the options") }
        if (maxSelection != null && default.size > maxSelection) keys.problem(DEFAULT, "more than max-selection ($maxSelection) values")
        return TemplateParameter.Select(name, common.label, common.hint, common.required, multiple, options, maxSelection, default)
    }

    private fun crsList(common: Common): TemplateParameter.CrsList? {
        val key = keys["list"] ?: return null.also { keys.problem("list", "required") }
        val list =
            TemplateList.byKey(key)
                ?: return null.also { keys.problem("list", "'$key' is not one of ${TemplateList.entries.joinToString { it.key }}") }
        val default = default(list.multiple)
        TemplateFields.staticValues(list)?.let { allowed ->
            default.filter { it !in allowed }.forEach { keys.problem(DEFAULT, "'$it' is not in the ${list.key} list") }
        }
        return TemplateParameter.CrsList(name, common.label, common.hint, common.required, list, default)
    }

    private fun person(common: Common): TemplateParameter.Person {
        val multiple = keys.bool(MULTIPLE) ?: false
        return TemplateParameter.Person(name, common.label, common.hint, common.required, multiple, default(multiple))
    }

    companion object {
        private const val TEXT = "text"
        private const val SELECT = "select"
        private const val CRS_LIST = "crs-list"
        private const val PERSON = "person"
        private const val DEFAULT = "default"
        private const val MULTIPLE = "multiple"
        private const val OPTIONS = "options"
        private const val MAX_SELECTION = "max-selection"
        private const val PATTERN = "pattern"
        private const val MAX_LENGTH = "max-length"
        private val NAME = Regex("[A-Z][A-Z0-9_]*")
        private val COMMON_KEYS = setOf("label", "hint", "type", "required", DEFAULT)
        private val TYPE_KEYS =
            mapOf(
                TEXT to setOf(PATTERN, "message", MAX_LENGTH),
                SELECT to setOf(OPTIONS, MULTIPLE, MAX_SELECTION),
                CRS_LIST to setOf("list"),
                PERSON to setOf(MULTIPLE),
            )
    }
}
