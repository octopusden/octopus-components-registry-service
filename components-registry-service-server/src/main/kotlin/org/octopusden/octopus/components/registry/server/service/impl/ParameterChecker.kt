package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.model.ComponentTemplate
import org.octopusden.octopus.components.registry.server.model.TemplateParameter
import org.octopusden.octopus.components.registry.server.service.EmployeeStatus
import org.octopusden.octopus.components.registry.server.service.ListValues
import org.octopusden.octopus.components.registry.server.service.impl.ActiveStatus

/** One failed parameter check, with a message a creator can act on. */
data class ParameterProblem(
    val parameter: String,
    val check: Check,
    val message: String,
) {
    enum class Check {
        P1_KNOWN,
        P2_REQUIRED,
        P3_COUNT,
        P4_MAX_LENGTH,
        P5_PATTERN,
        P6_OPTION,
        P7_LIST_VALUE,
        P8_ACTIVE_EMPLOYEE,
        P9_MAX_SELECTION,
    }
}

/**
 * The values the rest of a dry run uses, by parameter name — defaults applied, blanks dropped,
 * duplicates removed — and every failed check.
 */
data class ParameterValues(
    val values: Map<String, List<String>>,
    val problems: List<ParameterProblem>,
)

/**
 * Checks submitted values against a template's parameters with every check P1–P9 (Decision 5)
 * and reports every failure. Blank values are dropped and a repeated value counts once, for any
 * parameter. A parameter absent or left empty takes its default, [TemplateParameter.CURRENT_USER]
 * being the caller; an empty optional parameter without a default passes and adds nothing. When
 * the employee service cannot answer, P8 passes, as on create.
 */
class ParameterChecker(
    private val lists: ListValues,
    private val employees: EmployeeStatus,
) {
    fun check(
        template: ComponentTemplate,
        submitted: Map<String, List<String>>,
        caller: String,
    ): ParameterValues {
        val problems = mutableListOf<ParameterProblem>()
        val values =
            template.parameters.associate { parameter ->
                val given = submitted[parameter.name].orEmpty().filter { it.isNotBlank() }.distinct()
                val value = given.ifEmpty { defaultOf(parameter, caller) }
                problems += Checks(parameter, value).run()
                parameter.name to value
            }
        val defined = template.parameters.map { it.name }.toSet()
        submitted.keys.filter { it !in defined }.forEach {
            problems += ParameterProblem(it, ParameterProblem.Check.P1_KNOWN, "'$it' is not a parameter of this template")
        }
        return ParameterValues(values, problems)
    }

    private fun defaultOf(
        parameter: TemplateParameter,
        caller: String,
    ): List<String> =
        when (parameter) {
            is TemplateParameter.Text -> listOfNotNull(parameter.default)
            is TemplateParameter.Select -> parameter.default
            is TemplateParameter.CrsList -> parameter.default
            is TemplateParameter.Person -> parameter.default.map { if (it == TemplateParameter.CURRENT_USER) caller else it }
        }

    private inner class Checks(
        private val parameter: TemplateParameter,
        private val value: List<String>,
    ) {
        private val problems = mutableListOf<ParameterProblem>()

        fun run(): List<ParameterProblem> {
            if (value.isEmpty()) {
                if (parameter.required) fail(ParameterProblem.Check.P2_REQUIRED, "${parameter.label} is required")
                return problems
            }
            if (!parameter.multiple && value.size > 1) fail(ParameterProblem.Check.P3_COUNT, "${parameter.label} takes one value")
            when (parameter) {
                is TemplateParameter.Text -> text(parameter)
                is TemplateParameter.Select -> select(parameter)
                is TemplateParameter.CrsList -> crsList(parameter)
                is TemplateParameter.Person -> person()
            }
            return problems
        }

        private fun fail(
            check: ParameterProblem.Check,
            message: String,
        ) {
            problems += ParameterProblem(parameter.name, check, message)
        }

        private fun text(parameter: TemplateParameter.Text) {
            val text = value.first()
            parameter.maxLength?.let {
                if (text.length >
                    it
                ) {
                    fail(ParameterProblem.Check.P4_MAX_LENGTH, "${parameter.label} is at most $it characters")
                }
            }
            parameter.pattern?.let { pattern ->
                if (!Regex(pattern).matches(text)) {
                    fail(ParameterProblem.Check.P5_PATTERN, parameter.message ?: "${parameter.label} must match $pattern")
                }
            }
        }

        private fun select(parameter: TemplateParameter.Select) {
            value.filter { it !in parameter.options }.forEach {
                fail(ParameterProblem.Check.P6_OPTION, "'$it' is not one of ${parameter.options.joinToString()}")
            }
            parameter.maxSelection?.let {
                if (value.size > it) fail(ParameterProblem.Check.P9_MAX_SELECTION, "${parameter.label} takes at most $it values")
            }
        }

        private fun crsList(parameter: TemplateParameter.CrsList) {
            val allowed = lists.values(parameter.list)
            value.filter { it !in allowed }.forEach {
                fail(ParameterProblem.Check.P7_LIST_VALUE, "'$it' is not in the ${parameter.list.key} list")
            }
        }

        private fun person() =
            value.forEach { login ->
                when (employees.of(login)) {
                    ActiveStatus.INACTIVE -> fail(ParameterProblem.Check.P8_ACTIVE_EMPLOYEE, "'$login' is not an active employee")
                    ActiveStatus.UNKNOWN -> fail(ParameterProblem.Check.P8_ACTIVE_EMPLOYEE, "'$login' is not a known employee")
                    ActiveStatus.ACTIVE, ActiveStatus.UNAVAILABLE, ActiveStatus.DISABLED -> Unit
                }
            }
    }
}
