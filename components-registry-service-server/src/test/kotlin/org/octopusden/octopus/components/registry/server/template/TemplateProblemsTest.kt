package org.octopusden.octopus.components.registry.server.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TemplateProblemsTest {
    private fun rendered(
        template: ComponentTemplate = parsedTemplate(),
        overrides: Map<String, List<String>> = emptyMap(),
    ) = TemplateRenderer.render(template, EXAMPLE_VALUES, overrides, EXAMPLE_DEFAULTS, jiraTaskKey = null, changeComment = null)

    private fun withRule(
        path: String,
        pattern: String,
    ) = parsedTemplate(
        exampleTemplate().apply { put("rules", linkedMapOf(path to linkedMapOf("pattern" to pattern, "message" to "Broken."))) },
    )

    @Test
    @DisplayName("Decision 7: a rule failing on a field built from parameters names the field and those parameters")
    fun ruleOnParameters() {
        val template = withRule("name", "^[a-z]+-plugin-x.*$")

        val problems = TemplateProblems.rules(template, rendered(template))

        assertEquals(
            listOf(TemplateProblem(listOf("name"), setOf("CLIENT_CODE", "PLUGIN_CODE"), templateProblem = false, "name: Broken.")),
            problems,
        )
    }

    @Test
    @DisplayName("Decision 7: every rule is checked and every failure collected")
    fun everyRule() {
        val template =
            parsedTemplate(
                exampleTemplate().apply {
                    put(
                        "rules",
                        linkedMapOf(
                            "name" to linkedMapOf("pattern" to "x.*", "message" to "m1"),
                            "displayName" to linkedMapOf("pattern" to "x.*", "message" to "m2"),
                            "clientCode" to linkedMapOf("pattern" to "ACME", "message" to "m3"),
                        ),
                    )
                },
            )

        assertEquals(listOf("name", "displayName"), TemplateProblems.rules(template, rendered(template)).flatMap { it.fields })
    }

    @Test
    @DisplayName("Decision 9: a create failure on a field built from parameters names them")
    fun createFailureOnParameters() {
        val problem = TemplateProblems.create("name: a component with name 'acme-plugin-core' already exists", rendered())

        assertEquals(listOf("name"), problem.fields)
        assertEquals(setOf("CLIENT_CODE", "PLUGIN_CODE"), problem.parameters)
        assertEquals(false, problem.templateProblem)
    }

    @Test
    @DisplayName("Decision 9: a create failure on a fixed field is a template problem")
    fun createFailureOnFixedField() {
        val problem = TemplateProblems.create("Field 'jira.projectKey' is not editable", rendered())

        assertEquals(listOf("baseConfiguration.jira.projectKey"), problem.fields)
        assertEquals(emptySet<String>(), problem.parameters)
        assertEquals(true, problem.templateProblem)
    }

    @Test
    @DisplayName("Decision 9: a create failure on a field the template leaves unset is a template problem")
    fun createFailureOnUnsetField() {
        val problem = TemplateProblems.create("releaseManager is required when distribution is explicit and external", rendered())

        assertEquals(listOf("releaseManager"), problem.fields)
        assertEquals(true, problem.templateProblem)
    }

    @Test
    @DisplayName("Decision 9: of the paths a message concerns, only those the template set are named")
    fun onlySetPaths() {
        val problem = TemplateProblems.create("artifactIds: invalid artifact 'x y' — literal IDs only", rendered())

        assertEquals(listOf("artifactIds[0].groupPattern", "artifactIds[0].mode", "artifactIds[0].artifactTokens"), problem.fields)
        assertEquals(setOf("CLIENT_CODE", "PLUGIN_CODE"), problem.parameters)
    }

    @Test
    @DisplayName("an invalid override is reported on the overridden field, not as a template problem")
    fun overrideProblem() {
        val problem =
            TemplateProblems.create(
                "Field 'jira.projectKey' is not editable",
                rendered(overrides = mapOf("baseConfiguration.jira.projectKey" to listOf("OTHER"))),
            )

        assertEquals(listOf("baseConfiguration.jira.projectKey"), problem.fields)
        assertEquals(false, problem.templateProblem)
    }

    @Test
    @DisplayName("Decision 9: a message attributed to no field is reported without one")
    fun unattributed() {
        assertEquals(
            TemplateProblem(emptyList(), emptySet(), templateProblem = false, "Invalid version range: '[1,'"),
            TemplateProblems.create("Invalid version range: '[1,'", rendered()),
        )
    }

    @Test
    @DisplayName("Decision 4: a fixed label not in the labels dictionary is a template problem on labels")
    fun fixedLabel() {
        assertEquals(emptyList<TemplateProblem>(), TemplateProblems.fixedLabels(parsedTemplate(), setOf("plugin", "ui")))

        val problems = TemplateProblems.fixedLabels(parsedTemplate(), setOf("ui"))

        assertEquals(listOf(listOf("labels")), problems.map { it.fields })
        assertEquals(true, problems.single().templateProblem)
    }
}
