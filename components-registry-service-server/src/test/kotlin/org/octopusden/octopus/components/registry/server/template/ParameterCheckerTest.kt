package org.octopusden.octopus.components.registry.server.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.profile.ComponentProfile
import org.octopusden.octopus.components.registry.server.service.impl.ActiveStatus
import org.octopusden.octopus.components.registry.server.template.ParameterProblem.Check

private const val CALLER = "jdoe"

fun text(
    name: String,
    required: Boolean = true,
    pattern: String? = null,
    message: String? = null,
    maxLength: Int? = null,
    default: String? = null,
) = TemplateParameter.Text(name, "Label of $name", null, required, pattern, message, maxLength, default)

fun select(
    name: String,
    options: List<String>,
    required: Boolean = true,
    multiple: Boolean = false,
    maxSelection: Int? = null,
    default: List<String> = emptyList(),
) = TemplateParameter.Select(name, "Label of $name", null, required, multiple, options, maxSelection, default)

fun crsList(
    name: String,
    list: TemplateList,
    required: Boolean = true,
    default: List<String> = emptyList(),
) = TemplateParameter.CrsList(name, "Label of $name", null, required, list, default)

fun person(
    name: String,
    required: Boolean = true,
    multiple: Boolean = false,
    default: List<String> = emptyList(),
) = TemplateParameter.Person(name, "Label of $name", null, required, multiple, default)

fun templateWith(vararg parameters: TemplateParameter) =
    ComponentTemplate(
        id = TEMPLATE_ID,
        title = "Client plugin",
        description = "A plugin built for one client.",
        order = 100,
        version = 3,
        classification = ComponentProfile.Classification(external = true, explicit = ComponentProfile.Explicit.FALSE, solution = false),
        parameters = parameters.toList(),
        fields = emptyMap(),
        overridable = emptyList(),
        rules = emptyList(),
    )

/** Labels in the dictionary are `plugin` and `ui`; the two other lists are the registry's enums. */
private val LISTS =
    ListValues { list ->
        when (list) {
            TemplateList.LABELS -> setOf("plugin", "ui")
            else -> checkNotNull(TemplateFields.staticValues(list))
        }
    }

/** `jdoe` and `asmith` are active, `left` has left the company; anyone else is unknown. */
private val EMPLOYEES =
    EmployeeStatus { login ->
        when (login) {
            "jdoe", "asmith" -> ActiveStatus.ACTIVE
            "left" -> ActiveStatus.INACTIVE
            else -> ActiveStatus.UNKNOWN
        }
    }

class ParameterCheckerTest {
    private val checker = ParameterChecker(LISTS, EMPLOYEES)

    private fun check(
        template: ComponentTemplate,
        vararg values: Pair<String, List<String>>,
    ) = checker.check(template, values.toMap(), CALLER)

    private fun failedChecks(
        template: ComponentTemplate,
        vararg values: Pair<String, List<String>>,
    ) = check(template, *values).problems.map { it.parameter to it.check }

    @Test
    @DisplayName("P1: a value for a parameter the template does not define fails, naming it")
    fun p1UnknownParameter() {
        val template = templateWith(text("PLUGIN_CODE"))

        assertEquals(listOf("SUFFIX" to Check.P1_KNOWN), failedChecks(template, "PLUGIN_CODE" to listOf("CORE"), "SUFFIX" to listOf("x")))
        assertEquals(emptyList<Any>(), failedChecks(template, "PLUGIN_CODE" to listOf("CORE")))
    }

    @Test
    @DisplayName("P2: a required parameter missing or empty fails; given, it passes")
    fun p2Required() {
        val template = templateWith(text("PLUGIN_CODE"))

        assertEquals(listOf("PLUGIN_CODE" to Check.P2_REQUIRED), failedChecks(template))
        assertEquals(listOf("PLUGIN_CODE" to Check.P2_REQUIRED), failedChecks(template, "PLUGIN_CODE" to listOf("")))
        assertEquals(emptyList<Any>(), failedChecks(template, "PLUGIN_CODE" to listOf("CORE")))
    }

    @Test
    @DisplayName("P3: several values for a single-value parameter fail; one passes")
    fun p3NumberOfValues() {
        val template = templateWith(select("CLIENT_CODE", listOf("ACME", "GLOBEX")))

        assertEquals(listOf("CLIENT_CODE" to Check.P3_COUNT), failedChecks(template, "CLIENT_CODE" to listOf("ACME", "GLOBEX")))
        assertEquals(emptyList<Any>(), failedChecks(template, "CLIENT_CODE" to listOf("ACME")))
    }

    @Test
    @DisplayName("P4: a text longer than max-length fails; at the limit it passes")
    fun p4MaxLength() {
        val template = templateWith(text("SUFFIX", maxLength = 3))

        assertEquals(listOf("SUFFIX" to Check.P4_MAX_LENGTH), failedChecks(template, "SUFFIX" to listOf("abcd")))
        assertEquals(emptyList<Any>(), failedChecks(template, "SUFFIX" to listOf("abc")))
    }

    @Test
    @DisplayName("P5: a text not matching the pattern fails with the template's message")
    fun p5PatternWithMessage() {
        val message = "3–16 upper-case letters or digits, starting with a letter."
        val template = templateWith(text("PLUGIN_CODE", pattern = "^[A-Z][A-Z0-9]{2,15}$", message = message))

        val problem = check(template, "PLUGIN_CODE" to listOf("core")).problems.single()

        assertEquals("PLUGIN_CODE" to Check.P5_PATTERN, problem.parameter to problem.check)
        assertEquals(message, problem.message)
        assertEquals(emptyList<Any>(), failedChecks(template, "PLUGIN_CODE" to listOf("CORE")))
    }

    @Test
    @DisplayName("P5: without a message the failure still says what is wrong")
    fun p5PatternWithoutMessage() {
        val template = templateWith(text("PLUGIN_CODE", pattern = "[A-Z]+"))

        val problem = check(template, "PLUGIN_CODE" to listOf("core")).problems.single()

        assertEquals(Check.P5_PATTERN, problem.check)
        requireNotNull(problem.message.takeIf { it.isNotBlank() }) { "the default P5 message must not be blank" }
    }

    @Test
    @DisplayName("P6, Decision 13: a client code not among the select's options fails; a listed one passes")
    fun p6ClientCodeOption() {
        val template = templateWith(select("CLIENT_CODE", listOf("ACME", "GLOBEX")))

        assertEquals(listOf("CLIENT_CODE" to Check.P6_OPTION), failedChecks(template, "CLIENT_CODE" to listOf("XYZ")))
        assertEquals(emptyList<Any>(), failedChecks(template, "CLIENT_CODE" to listOf("GLOBEX")))
    }

    @Test
    @DisplayName("P7: a label outside the labels dictionary fails; a dictionary label passes")
    fun p7Labels() {
        val template = templateWith(crsList("EXTRA", TemplateList.LABELS))

        assertEquals(listOf("EXTRA" to Check.P7_LIST_VALUE), failedChecks(template, "EXTRA" to listOf("ui", "nosuchlabel")))
        assertEquals(emptyList<Any>(), failedChecks(template, "EXTRA" to listOf("plugin", "ui")))
    }

    @Test
    @DisplayName("P7: build systems and escrow generation modes are checked against their enums")
    fun p7Enums() {
        val template = templateWith(crsList("BS", TemplateList.BUILD_SYSTEMS), crsList("ESCROW", TemplateList.ESCROW_GENERATION))

        assertEquals(
            listOf("BS" to Check.P7_LIST_VALUE, "ESCROW" to Check.P7_LIST_VALUE),
            failedChecks(template, "BS" to listOf("ANT"), "ESCROW" to listOf("SOMETIMES")),
        )
        assertEquals(emptyList<Any>(), failedChecks(template, "BS" to listOf("GRADLE"), "ESCROW" to listOf("UNSUPPORTED")))
    }

    @Test
    @DisplayName("P8: an inactive or unknown login fails; an active one passes")
    fun p8ActiveEmployee() {
        val template = templateWith(person("RELEASE_MANAGERS", multiple = true))

        assertEquals(
            listOf("RELEASE_MANAGERS" to Check.P8_ACTIVE_EMPLOYEE, "RELEASE_MANAGERS" to Check.P8_ACTIVE_EMPLOYEE),
            failedChecks(template, "RELEASE_MANAGERS" to listOf("jdoe", "left", "ghost")),
        )
        assertEquals(emptyList<Any>(), failedChecks(template, "RELEASE_MANAGERS" to listOf("jdoe", "asmith")))
    }

    @Test
    @DisplayName("P8, as on create: when the employee service is unavailable or disabled, P8 passes")
    fun p8ServiceUnavailable() {
        val template = templateWith(person("COMPONENT_OWNER"))

        listOf(ActiveStatus.UNAVAILABLE, ActiveStatus.DISABLED).forEach { status ->
            val result = ParameterChecker(LISTS) { status }.check(template, mapOf("COMPONENT_OWNER" to listOf("anyone")), CALLER)
            assertEquals(emptyList<ParameterProblem>(), result.problems, "status $status")
        }
    }

    @Test
    @DisplayName("P9: more options than max-selection fails; at the limit it passes")
    fun p9MaxSelection() {
        val template = templateWith(select("TARGETS", listOf("a", "b", "c"), multiple = true, maxSelection = 2))

        assertEquals(listOf("TARGETS" to Check.P9_MAX_SELECTION), failedChecks(template, "TARGETS" to listOf("a", "b", "c")))
        assertEquals(emptyList<Any>(), failedChecks(template, "TARGETS" to listOf("a", "b")))
    }

    @Test
    @DisplayName("Decision 5: an absent parameter takes its default; current-user is the caller")
    fun defaults() {
        val template =
            templateWith(
                text("SUFFIX", required = false, default = "core"),
                person("COMPONENT_OWNER", default = listOf(TemplateParameter.CURRENT_USER)),
                select("CLIENT_CODE", listOf("ACME", "GLOBEX"), default = listOf("ACME")),
            )

        val result = check(template)

        assertEquals(emptyList<ParameterProblem>(), result.problems)
        assertEquals(
            mapOf("SUFFIX" to listOf("core"), "COMPONENT_OWNER" to listOf(CALLER), "CLIENT_CODE" to listOf("ACME")),
            result.values,
        )
    }

    @Test
    @DisplayName("a value given, even empty, replaces the default")
    fun givenValueReplacesDefault() {
        val template = templateWith(text("SUFFIX", required = false, default = "core"))

        assertEquals(mapOf("SUFFIX" to emptyList<String>()), check(template, "SUFFIX" to listOf("")).values)
    }

    @Test
    @DisplayName("an empty optional parameter passes every check and adds nothing")
    fun emptyOptional() {
        val template = templateWith(text("SUFFIX", required = false, pattern = "[a-z]+"), select("TIER", listOf("a"), required = false))

        val result = check(template, "SUFFIX" to emptyList(), "TIER" to listOf(" "))

        assertEquals(emptyList<ParameterProblem>(), result.problems)
        assertEquals(mapOf("SUFFIX" to emptyList<String>(), "TIER" to emptyList()), result.values)
    }

    @Test
    @DisplayName("a value given twice for a multi-value parameter counts once")
    fun duplicateCountsOnce() {
        val template = templateWith(select("TARGETS", listOf("a", "b"), multiple = true, maxSelection = 1))

        val result = check(template, "TARGETS" to listOf("a", "a"))

        assertEquals(emptyList<ParameterProblem>(), result.problems)
        assertEquals(mapOf("TARGETS" to listOf("a")), result.values)
    }

    @Test
    @DisplayName("several failures are all reported, each on its parameter")
    fun severalFailures() {
        val template =
            templateWith(
                text("PLUGIN_CODE", pattern = "[A-Z]+"),
                text("PLUGIN_NAME"),
                select("CLIENT_CODE", listOf("ACME")),
            )

        assertEquals(
            listOf("PLUGIN_CODE" to Check.P5_PATTERN, "PLUGIN_NAME" to Check.P2_REQUIRED, "CLIENT_CODE" to Check.P6_OPTION),
            failedChecks(template, "PLUGIN_CODE" to listOf("core"), "CLIENT_CODE" to listOf("XYZ")),
        )
    }
}
