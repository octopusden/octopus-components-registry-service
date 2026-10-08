package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.ProfileLoad
import org.octopusden.octopus.components.registry.server.support.SOLUTION_NAME_MESSAGE
import org.octopusden.octopus.components.registry.server.support.SOLUTION_NAME_PATTERN
import org.octopusden.octopus.components.registry.server.support.designExampleProperties
import org.octopusden.octopus.components.registry.server.support.profileProperties
import org.octopusden.octopus.components.registry.server.support.rule

class ComponentProfileParserTest {
    @Test
    @DisplayName("the four profiles of the design example parse to four live profiles in order")
    fun designExample() {
        val result = ComponentProfileParser.parse(designExampleProperties())

        assertTrue(result.usable, "problems: ${result.problems} ${result.entries}")
        assertEquals(listOf("regular-external", "regular-internal", "solution", "dmp-bundle"), result.profiles.map { it.id })
        assertTrue(result.entries.all { it.status == ProfileLoad.Entry.Status.LIVE })

        val solution = result.profiles.single { it.id == "solution" }
        assertEquals(
            ComponentProfile.Classification(external = true, explicit = ComponentProfile.Explicit.TRUE, solution = true),
            solution.classification,
        )
        assertEquals(30, solution.order)
        assertEquals(listOf(ComponentProfile.FieldRule("name", SOLUTION_NAME_PATTERN, SOLUTION_NAME_MESSAGE)), solution.rules)

        val internal = result.profiles.single { it.id == "regular-internal" }
        assertEquals(
            ComponentProfile.Classification(external = false, explicit = ComponentProfile.Explicit.ASK, solution = false),
            internal.classification,
        )
    }

    @Test
    @DisplayName("profiles with the same order are sorted by id")
    fun orderTiesById() {
        val result =
            ComponentProfileParser.parse(profileProperties("beta", order = "5") + profileProperties("alpha", order = "5"))

        assertEquals(listOf("alpha", "beta"), result.profiles.map { it.id })
    }

    @Test
    @DisplayName("entries are listed by id, whatever order they are configured in")
    fun entriesById() {
        val result = ComponentProfileParser.parse(profileProperties("beta") + profileProperties("alpha", kind = "template"))

        assertEquals(listOf("alpha", "beta"), result.entries.map { it.id })
    }

    @Test
    @DisplayName("a rule on a dotted, indexed path keeps the whole path")
    fun dottedRulePath() {
        val result =
            ComponentProfileParser.parse(
                profileProperties(
                    "internal",
                    rules = mapOf("baseConfiguration.mavenArtifacts[0].artifactPattern" to rule("^pay-.*$", "Starts with pay-.")),
                ),
            )

        assertTrue(result.usable, "problems: ${result.entries}")
        assertEquals(
            listOf(ComponentProfile.FieldRule("baseConfiguration.mavenArtifacts[0].artifactPattern", "^pay-.*$", "Starts with pay-.")),
            result.profiles.single().rules,
        )
    }

    @Test
    @DisplayName("a broken template entry fails with its own problems; the regular profiles stay live and the load usable")
    fun brokenTemplateEntryFails() {
        val template =
            mapOf(
                "client-plugin.kind" to "template",
                "client-plugin.version" to "3",
                "client-plugin.parameters.CLIENT_CODE.type" to "crs-list",
                "client-plugin.fields.name" to "{{ CLIENT_CODE | lower }}-plugin",
            )

        val result = ComponentProfileParser.parse(designExampleProperties() + template)

        assertTrue(result.usable)
        assertEquals(4, result.profiles.size)
        val entry = result.entries.single { it.id == "client-plugin" }
        assertEquals(ProfileLoad.Entry.Status.FAILED, entry.status)
        assertEquals("template", entry.kind)
        assertTrue("client-plugin.title: required" in entry.problems, "problems: ${entry.problems}")
    }

    @Test
    @DisplayName("only template entries: the configuration is not usable")
    fun onlyTemplates() {
        val result = ComponentProfileParser.parse(mapOf("client-plugin.kind" to "template"))

        assertFalse(result.usable)
        assertEquals(listOf("at least one regular profile is required"), result.problems)
    }

    @Test
    @DisplayName("no properties at all: the configuration is not usable")
    fun empty() {
        val result = ComponentProfileParser.parse(emptyMap())

        assertFalse(result.usable)
        assertEquals(listOf("at least one regular profile is required"), result.problems)
    }

    private fun entryOf(
        properties: Map<String, String>,
        id: String,
    ): ProfileLoad.Entry = ComponentProfileParser.parse(properties).entries.single { it.id == id }

    private fun assertInvalid(
        properties: Map<String, String>,
        id: String,
        problemPrefix: String,
    ): ProfileLoad.Entry {
        val result = ComponentProfileParser.parse(properties)
        val entry = result.entries.single { it.id == id }
        assertEquals(ProfileLoad.Entry.Status.FAILED, entry.status, "expected $id to fail")
        assertTrue(entry.problems.any { it.startsWith(problemPrefix) }, "expected a problem starting '$problemPrefix' in ${entry.problems}")
        assertFalse(result.usable)
        assertTrue(result.profiles.none { it.id == id })
        return entry
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = ["kind", "title", "description", "order", "classification.external", "classification.explicit"])
    @DisplayName("a missing required key makes the profile invalid, naming the key")
    fun missingRequiredKey(key: String) {
        val properties = profileProperties("internal").filterKeys { it != "internal.$key" }

        assertInvalid(properties, "internal", "internal.$key:")
    }

    @Test
    @DisplayName("a missing classification.solution means not a solution")
    fun solutionDefaultsToFalse() {
        val result = ComponentProfileParser.parse(profileProperties("internal", solution = null))

        assertFalse(
            result.profiles
                .single()
                .classification.solution,
        )
    }

    @ParameterizedTest(name = "unknown {0}")
    @ValueSource(strings = ["maintainer", "classification.colour", "rules.name.severity"])
    @DisplayName("an unknown key at profile, classification or rule level makes the profile invalid, naming the key")
    fun unknownKey(key: String) {
        val properties =
            profileProperties("internal", rules = mapOf("name" to rule("^.*$", "Any."))) + ("internal.$key" to "x")

        assertInvalid(properties, "internal", "internal.$key:")
    }

    @ParameterizedTest(name = "{0} = ''{1}''")
    @CsvSource(
        "kind, special",
        "order, ten",
        "order, 1.5",
        "classification.external, yes",
        "classification.explicit, maybe",
        "classification.solution, maybe",
    )
    @DisplayName("a value outside the allowed values makes the profile invalid, naming the key and the value")
    fun valueOutsideAllowed(
        key: String,
        value: String,
    ) {
        val properties = profileProperties("internal") + ("internal.$key" to value)

        val entry = assertInvalid(properties, "internal", "internal.$key:")
        assertTrue(entry.problems.any { it.startsWith("internal.$key:") && it.contains("'$value'") }, "${entry.problems}")
    }

    @ParameterizedTest(name = "{0} = blank")
    @ValueSource(strings = ["title", "description"])
    @DisplayName("a blank title or description makes the profile invalid")
    fun blankText(key: String) {
        assertInvalid(profileProperties("internal") + ("internal.$key" to " "), "internal", "internal.$key:")
    }

    @ParameterizedTest(name = "id ''{0}''")
    @ValueSource(strings = ["Solution", "dmp_bundle", "my profile"])
    @DisplayName("an id that is not lowercase letters, digits and '-' makes the profile invalid, naming the id")
    fun invalidId(id: String) {
        assertInvalid(profileProperties(id), id, "$id:")
    }

    @ParameterizedTest(name = "external={0}, explicit={1}")
    @CsvSource("true, ask", "true, false", "false, true")
    @DisplayName("a solution profile must be external and explicit")
    fun solutionMustBeExternalAndExplicit(
        external: String,
        explicit: String,
    ) {
        val properties = profileProperties("solution", external = external, explicit = explicit, solution = "true")

        assertInvalid(properties, "solution", "solution.classification.solution:")
    }

    @Test
    @DisplayName("a rule pattern that does not compile makes the profile invalid, naming the rule's path")
    fun ruleBadPattern() {
        assertInvalid(profileProperties("internal", rules = mapOf("name" to rule("(", "Broken."))), "internal", "internal.rules.name")
    }

    @ParameterizedTest(name = "rule without {0}")
    @ValueSource(strings = ["pattern", "message"])
    @DisplayName("a rule missing its pattern or message makes the profile invalid, naming the rule's path")
    fun ruleMissingKey(missing: String) {
        val properties =
            profileProperties("internal", rules = mapOf("name" to rule("^.*$", "Any."))).filterKeys { it != "internal.rules.name.$missing" }

        assertInvalid(properties, "internal", "internal.rules.name")
    }

    @Test
    @DisplayName("a rule with a blank message makes the profile invalid")
    fun ruleBlankMessage() {
        assertInvalid(profileProperties("internal", rules = mapOf("name" to rule("^.*$", " "))), "internal", "internal.rules.name")
    }

    @Test
    @DisplayName("a rule on a path outside the list makes the profile invalid, naming the path")
    fun ruleUnknownPath() {
        val properties = profileProperties("internal", rules = mapOf("componentOwner" to rule("^.*$", "Any.")))

        assertInvalid(properties, "internal", "internal.rules.componentOwner")
    }

    @Test
    @DisplayName("a rule written as a plain value instead of pattern and message makes the profile invalid")
    fun ruleScalar() {
        assertInvalid(profileProperties("internal") + ("internal.rules.name" to "^.*$"), "internal", "internal.rules.name")
    }

    @Test
    @DisplayName("every listed rule path is accepted")
    fun everyPathAccepted() {
        val rules = CreateRequestPaths.PATHS.associateWith { rule("^.*$", "Any.") }

        val result = ComponentProfileParser.parse(profileProperties("internal", rules = rules))

        assertTrue(result.usable, "${result.entries}")
        assertEquals(
            CreateRequestPaths.PATHS,
            result.profiles
                .single()
                .rules
                .map { it.path }
                .toSet(),
        )
    }

    @Test
    @DisplayName("every problem of a profile is reported, not just the first")
    fun everyProblemReported() {
        val entry = entryOf(profileProperties("internal", kind = "special", title = null), "internal")

        assertTrue(entry.problems.any { it.startsWith("internal.kind:") }, "${entry.problems}")
        assertTrue(entry.problems.any { it.startsWith("internal.title:") }, "${entry.problems}")
    }

    @Test
    @DisplayName("one invalid regular profile among valid ones: not usable, every entry listed with its status")
    fun oneInvalidAmongValid() {
        val result = ComponentProfileParser.parse(designExampleProperties() + profileProperties("broken", order = null))

        assertFalse(result.usable)
        assertEquals(5, result.entries.size)
        assertEquals(ProfileLoad.Entry.Status.FAILED, result.entries.single { it.id == "broken" }.status)
        assertTrue(result.entries.filter { it.id != "broken" }.all { it.status == ProfileLoad.Entry.Status.LIVE })
    }
}
