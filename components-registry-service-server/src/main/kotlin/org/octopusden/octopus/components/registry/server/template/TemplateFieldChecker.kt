package org.octopusden.octopus.components.registry.server.template

/**
 * The spec's field checks: each value against its field kind and the parameters it uses.
 * [parameters] are the ones that parsed; [declared] are every configured name, so a parameter
 * with its own problem is not also reported as undefined.
 */
internal class TemplateFieldChecker(
    parameters: List<TemplateParameter>,
    private val declared: Set<String>,
    private val keys: EntryKeys,
) {
    private val byName = parameters.associateBy { it.name }

    fun check(fields: Map<String, TemplateField>) =
        fields.values.forEach { field ->
            when (field) {
                is TemplateField.Single -> check("$FIELDS${field.path}", field, field.value)
                is TemplateField.Items -> field.items.forEachIndexed { index, item -> check("$FIELDS${field.path}[$index]", field, item) }
            }
        }

    private fun check(
        key: String,
        field: TemplateField,
        value: TemplateExpression,
    ) {
        (value.parameters - declared).forEach { keys.problem(key, "uses '{{ $it }}', which is not a parameter of this template") }
        val fixed = value.parameters.isEmpty()
        when (field.kind) {
            TemplateFields.Kind.FREE_TEXT -> freeText(key, value)
            TemplateFields.Kind.CRS_VALUE -> crsValue(key, field.path, value, fixed)
            TemplateFields.Kind.PERSON -> if (!fixed) {
                whole(
                    key,
                    value,
                    "a single person",
                ) { it is TemplateParameter.Person && !it.multiple }
            }
            TemplateFields.Kind.FREE_TEXT_LIST -> freeTextItem(key, value)
            TemplateFields.Kind.CRS_LIST ->
                if (!fixed) {
                    whole(key, value, "a crs-list of the labels list") {
                        it is TemplateParameter.CrsList &&
                            it.list == TemplateList.LABELS
                    }
                }
            TemplateFields.Kind.PEOPLE_LIST -> if (!fixed) whole(key, value, "a person") { it is TemplateParameter.Person }
            TemplateFields.Kind.FIXED_CHOICE -> fixedChoice(key, field.path, value, fixed)
        }
    }

    private fun freeText(
        key: String,
        value: TemplateExpression,
    ) = value.parameters.mapNotNull { byName[it] }.forEach {
        when {
            it is TemplateParameter.Person -> keys.problem(key, "a person parameter ('${it.name}') cannot go into free text")
            it.multiple -> keys.problem(key, "a multi-value parameter ('${it.name}') goes only into a list field")
        }
    }

    private fun crsValue(
        key: String,
        path: String,
        value: TemplateExpression,
        fixed: Boolean,
    ) {
        val list = TemplateFields.CRS_VALUE_LISTS.getValue(path)
        if (fixed) {
            val literal = value.literal()
            TemplateFields.staticValues(list)?.let { if (literal !in it) keys.problem(key, "'$literal' is not in the ${list.key} list") }
        } else {
            whole(key, value, "a crs-list of the ${list.key} list") { it is TemplateParameter.CrsList && it.list == list }
        }
    }

    private fun freeTextItem(
        key: String,
        value: TemplateExpression,
    ) {
        val multiValue = value.parameters.mapNotNull { byName[it] }.any { it.multiple && it !is TemplateParameter.Person }
        if (multiValue) whole(key, value, "a select") { it is TemplateParameter.Select } else freeText(key, value)
    }

    private fun fixedChoice(
        key: String,
        path: String,
        value: TemplateExpression,
        fixed: Boolean,
    ) {
        val allowed = TemplateFields.FIXED_CHOICES.getValue(path)
        when {
            !fixed -> keys.problem(key, "takes a fixed value only, one of ${allowed.joinToString()}")
            value.literal() !in allowed -> keys.problem(key, "'${value.literal()}' is not one of ${allowed.joinToString()}")
        }
    }

    /** The value must be exactly `{{ NAME }}`, and a known parameter must be [expected]. */
    private fun whole(
        key: String,
        value: TemplateExpression,
        expected: String,
        accepts: (TemplateParameter) -> Boolean,
    ) {
        val name = value.wholeParameter
        when {
            name == null -> keys.problem(key, "takes a whole value: exactly {{ NAME }}, with no text around it and no filter")
            byName[name]?.let(accepts) == false -> keys.problem(key, "'{{ $name }}' must be $expected parameter")
        }
    }

    companion object {
        const val FIELDS = "fields."
    }
}

internal fun TemplateExpression.literal(): String = parts.joinToString("") { (it as? TemplateExpression.Part.Literal)?.text.orEmpty() }
