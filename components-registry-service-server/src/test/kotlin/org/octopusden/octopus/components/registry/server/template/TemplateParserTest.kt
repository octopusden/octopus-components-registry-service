package org.octopusden.octopus.components.registry.server.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.components.registry.server.util.ComponentProfileParser
import org.octopusden.octopus.components.registry.server.model.ProfileLoad
import org.octopusden.octopus.components.registry.server.support.designExampleProperties

const val TEMPLATE_ID = "client-plugin"

/** The `component-defaults` the design example relies on: the full version format and the VCS tag and branch. */
val EXAMPLE_DEFAULTS =
    mapOf(
        "baseConfiguration.jira.versionFormat" to "\$versionPrefix-\$baseVersionFormat",
        "baseConfiguration.vcsEntries[0].tag" to "\$module-\$version",
        "baseConfiguration.vcsEntries[0].branch" to "main",
    )

/** The design's example template, as YAML would nest it. */
fun exampleTemplate(): MutableMap<String, Any> =
    linkedMapOf(
        "kind" to "template",
        "title" to "Client plugin",
        "description" to "A plugin built for one client.",
        "version" to "3",
        "order" to "100",
        "classification" to linkedMapOf("solution" to "false", "external" to "true", "explicit" to "false"),
        "parameters" to
            linkedMapOf(
                "CLIENT_CODE" to linkedMapOf("label" to "Client code", "type" to "select", "options" to listOf("ACME", "GLOBEX")),
                "PLUGIN_CODE" to
                    linkedMapOf(
                        "label" to "Plugin code",
                        "type" to "text",
                        "pattern" to "^[A-Z][A-Z0-9]{2,15}$",
                        "message" to "3–16 upper-case letters or digits, starting with a letter.",
                    ),
                "PLUGIN_NAME" to linkedMapOf("label" to "Plugin name", "type" to "text"),
                "COMPONENT_OWNER" to linkedMapOf("label" to "Component owner", "type" to "person", "default" to "current-user"),
            ),
        "fields" to
            linkedMapOf(
                "name" to "{{ CLIENT_CODE | lower }}-plugin-{{ PLUGIN_CODE | lower }}",
                "displayName" to "{{ PLUGIN_NAME | upper }} for {{ CLIENT_CODE | upper }}",
                "componentOwner" to "{{ COMPONENT_OWNER }}",
                "clientCode" to "{{ CLIENT_CODE | upper }}",
                "labels" to listOf("plugin"),
                "artifactIds" to
                    listOf(
                        linkedMapOf(
                            "groupPattern" to "org.example.plugins.{{ CLIENT_CODE | lower }}",
                            "mode" to "EXPLICIT",
                            "artifactTokens" to listOf("{{ PLUGIN_CODE | lower }}"),
                        ),
                    ),
                "baseConfiguration" to
                    linkedMapOf(
                        "build" to linkedMapOf("buildSystem" to "GRADLE", "buildTasks" to "build"),
                        "vcsEntries" to
                            listOf(linkedMapOf("vcsPath" to "ssh://git@git.example.com/clients/{{ CLIENT_CODE | lower }}/plugin.git")),
                        "jira" to
                            linkedMapOf(
                                "projectKey" to "PLUGINS",
                                "versionPrefix" to "{{ CLIENT_CODE | lower }}-plugin-{{ PLUGIN_CODE | lower }}",
                            ),
                        "escrow" to linkedMapOf("generation" to "UNSUPPORTED"),
                    ),
            ),
        "overridable" to listOf("baseConfiguration.vcsEntries[0].vcsPath", "baseConfiguration.jira.projectKey"),
    )

/**
 * The design example with the values it otherwise takes from `component-defaults` fixed in the
 * template, so it is live without any defaults.
 */
@Suppress("UNCHECKED_CAST")
fun standaloneTemplate(): MutableMap<String, Any> =
    exampleTemplate().apply {
        at("fields.baseConfiguration.jira")["versionFormat"] = "\$versionPrefix-\$baseVersionFormat"
        val vcs = (at("fields.baseConfiguration")["vcsEntries"] as List<MutableMap<String, Any>>).single()
        vcs["branch"] = "main"
        vcs["tag"] = "\$module-\$version"
    }

/** [standaloneTemplate] as the flattened properties of entry [id]. */
fun standaloneTemplateProperties(id: String = TEMPLATE_ID): Map<String, String> = flatten(standaloneTemplate(), id)

/** Flattens nested maps and lists the way Spring flattens YAML: `a.b`, `a[0]`. */
fun flatten(
    value: Any,
    prefix: String,
): Map<String, String> =
    when (value) {
        is Map<*, *> -> value.entries.fold(linkedMapOf()) { acc, (key, child) -> acc.apply { putAll(flatten(child!!, "$prefix.$key")) } }
        is List<*> -> value.foldIndexed(linkedMapOf()) { index, acc, child -> acc.apply { putAll(flatten(child!!, "$prefix[$index]")) } }
        else -> mapOf(prefix to value.toString())
    }

@Suppress("UNCHECKED_CAST")
fun MutableMap<String, Any>.at(path: String): MutableMap<String, Any> =
    path.split('.').fold(this) { map, key -> map[key] as MutableMap<String, Any> }

class TemplateParserTest {
    private fun load(
        template: Map<String, Any>,
        defaults: Map<String, String> = EXAMPLE_DEFAULTS,
    ): ProfileLoad = ComponentProfileParser.parse(designExampleProperties() + flatten(template, TEMPLATE_ID), defaults)

    private fun problems(
        template: Map<String, Any>,
        defaults: Map<String, String> = EXAMPLE_DEFAULTS,
    ): List<String> = load(template, defaults).entries.single { it.id == TEMPLATE_ID }.problems

    private fun assertProblem(
        expectedKey: String,
        template: Map<String, Any>,
        defaults: Map<String, String> = EXAMPLE_DEFAULTS,
    ) {
        val problems = problems(template, defaults)
        assertTrue(problems.any { it.startsWith("$TEMPLATE_ID.$expectedKey:") }, "expected a problem on '$expectedKey', got $problems")
    }

    @Test
    @DisplayName("Decision 1: the design example parses to a live template: four parameters, fourteen fields, two overridable paths")
    fun designExampleIsLive() {
        val load = load(exampleTemplate())

        assertEquals(emptyList<String>(), load.entries.single { it.id == TEMPLATE_ID }.problems)
        assertEquals(ProfileLoad.Entry.Status.LIVE, load.entries.single { it.id == TEMPLATE_ID }.status)
        val template = load.templates.single()
        assertEquals(listOf("CLIENT_CODE", "PLUGIN_CODE", "PLUGIN_NAME", "COMPONENT_OWNER"), template.parameters.map { it.name })
        assertEquals(14, template.fields.size)
        assertEquals(listOf("baseConfiguration.vcsEntries[0].vcsPath", "baseConfiguration.jira.projectKey"), template.overridable)
        assertEquals(3, template.version)
        assertTrue(load.usable)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["title", "description", "order", "version", "fields"])
    @DisplayName("a missing required template key is a problem naming it")
    fun missingTemplateKey(key: String) {
        assertProblem(key, exampleTemplate().apply { remove(key) })
    }

    @ParameterizedTest(name = "classification.{0}")
    @ValueSource(strings = ["external", "explicit"])
    @DisplayName("a missing classification key is a problem naming it")
    fun missingClassificationKey(key: String) {
        assertProblem("classification.$key", exampleTemplate().apply { at("classification").remove(key) })
    }

    @ParameterizedTest(name = "parameters.PLUGIN_NAME.{0}")
    @ValueSource(strings = ["label", "type"])
    @DisplayName("a parameter's missing label or type is a problem naming it")
    fun missingParameterKey(key: String) {
        assertProblem("parameters.PLUGIN_NAME.$key", exampleTemplate().apply { at("parameters.PLUGIN_NAME").remove(key) })
    }

    @Test
    @DisplayName("unknown keys are problems naming them, at the template, classification, parameter and rule level")
    fun unknownKeys() {
        val template =
            exampleTemplate().apply {
                put("maintainer", "jdoe")
                at("classification")["internal"] = "true"
                at("parameters.PLUGIN_NAME")["placeholder"] = "Core API"
                put("rules", linkedMapOf("name" to linkedMapOf("pattern" to ".*", "message" to "m", "severity" to "warn")))
            }

        listOf("maintainer", "classification.internal", "parameters.PLUGIN_NAME.placeholder", "rules.name.severity")
            .forEach { assertProblem(it, template) }
    }

    @Test
    @DisplayName("explicit: ask is for regular profiles only")
    fun askIsNotATemplateClassification() {
        assertProblem("classification.explicit", exampleTemplate().apply { at("classification")["explicit"] = "ask" })
    }

    private fun withParameter(
        name: String,
        vararg keys: Pair<String, Any>,
        usedIn: String = "baseConfiguration.build.buildTasks",
    ) = exampleTemplate().apply {
        at("parameters")[name] = linkedMapOf<String, Any>("label" to "Label of $name").apply { putAll(keys) }
        at("fields.baseConfiguration.build")[usedIn.substringAfterLast('.')] = "build {{ $name }}"
    }

    @Test
    @DisplayName("a parameter name that is not upper-case letters, digits and '_' is a problem")
    fun badParameterName() {
        assertProblem("parameters.plugin_name", withParameter("plugin_name", "type" to "text"))
    }

    @Test
    @DisplayName("a key that does not apply to the type is a problem naming it: options on a text parameter")
    fun keyNotForType() {
        assertProblem("parameters.SUFFIX.options", withParameter("SUFFIX", "type" to "text", "options" to listOf("a", "b")))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["text", "crs-list"])
    @DisplayName("multiple on a text or crs-list parameter is a problem")
    fun multipleNotForType(type: String) {
        assertProblem(
            "parameters.SUFFIX.multiple",
            withParameter("SUFFIX", "type" to type, "list" to "build-systems", "multiple" to "true"),
        )
    }

    @Test
    @DisplayName("a select without options is a problem")
    fun selectWithoutOptions() {
        assertProblem("parameters.TIER.options", withParameter("TIER", "type" to "select"))
    }

    @Test
    @DisplayName("a select repeating an option is a problem")
    fun selectRepeatedOption() {
        assertProblem("parameters.TIER.options", withParameter("TIER", "type" to "select", "options" to listOf("gold", "gold")))
    }

    @ParameterizedTest(name = "list: {0}")
    @ValueSource(strings = ["environments", "client-codes"])
    @DisplayName("Decision 13: an unknown list, client-codes included, is a problem naming list")
    fun unknownList(list: String) {
        assertProblem("parameters.TIER.list", withParameter("TIER", "type" to "crs-list", "list" to list))
    }

    @Test
    @DisplayName("a pattern that does not compile is a problem")
    fun patternDoesNotCompile() {
        assertProblem("parameters.SUFFIX.pattern", withParameter("SUFFIX", "type" to "text", "pattern" to "[A-Z"))
    }

    @Test
    @DisplayName("max-length not above 0 is a problem")
    fun maxLengthNotPositive() {
        assertProblem("parameters.SUFFIX.max-length", withParameter("SUFFIX", "type" to "text", "max-length" to "0"))
    }

    @Test
    @DisplayName("max-selection not above 0 is a problem")
    fun maxSelectionNotPositive() {
        assertProblem(
            "parameters.TIER.max-selection",
            withParameter("TIER", "type" to "select", "options" to listOf("a", "b"), "multiple" to "true", "max-selection" to "-1"),
        )
    }

    @Test
    @DisplayName("max-selection without multiple: true is a problem")
    fun maxSelectionWithoutMultiple() {
        assertProblem(
            "parameters.TIER.max-selection",
            withParameter("TIER", "type" to "select", "options" to listOf("a", "b"), "max-selection" to "1"),
        )
    }

    @Test
    @DisplayName("a select default that is not an option is a problem")
    fun defaultNotAnOption() {
        assertProblem("parameters.TIER.default", withParameter("TIER", "type" to "select", "options" to listOf("a", "b"), "default" to "c"))
    }

    @Test
    @DisplayName("a text default that does not match the pattern is a problem")
    fun defaultNotMatchingPattern() {
        assertProblem("parameters.SUFFIX.default", withParameter("SUFFIX", "type" to "text", "pattern" to "[a-z]+", "default" to "X1"))
    }

    @Test
    @DisplayName("a text default longer than max-length is a problem")
    fun defaultTooLong() {
        assertProblem("parameters.SUFFIX.default", withParameter("SUFFIX", "type" to "text", "max-length" to "3", "default" to "abcd"))
    }

    @Test
    @DisplayName("several defaults for a single-value parameter are a problem")
    fun severalDefaultsForSingleValue() {
        assertProblem(
            "parameters.TIER.default",
            withParameter("TIER", "type" to "select", "options" to listOf("a", "b"), "default" to listOf("a", "b")),
        )
    }

    @Test
    @DisplayName("a crs-list default outside a static list is a problem")
    fun defaultNotInStaticList() {
        assertProblem("parameters.BS.default", withParameter("BS", "type" to "crs-list", "list" to "build-systems", "default" to "ANT"))
    }

    @Test
    @DisplayName("a parameter no field uses is a problem naming it")
    fun unusedParameter() {
        assertProblem(
            "parameters.PLUGIN",
            exampleTemplate().apply { at("parameters")["PLUGIN"] = linkedMapOf("label" to "Plugin", "type" to "text") },
        )
    }

    @Test
    @DisplayName("a field path outside the field table is a problem naming it")
    fun pathOutsideTable() {
        assertProblem("fields.systems[0]", exampleTemplate().apply { at("fields")["systems"] = listOf("CLASSIC") })
    }

    @Test
    @DisplayName("a field using a parameter the template does not define is a problem naming the field")
    fun undefinedParameter() {
        assertProblem("fields.clientCode", exampleTemplate().apply { at("fields")["clientCode"] = "{{ CLIENT }}" })
    }

    @Test
    @DisplayName("a person parameter in free text is a problem naming the field")
    fun personInFreeText() {
        val template = exampleTemplate().apply {
            at("fields")["displayName"] =
                "{{ COMPONENT_OWNER }} tools for {{ PLUGIN_NAME }} {{ CLIENT_CODE }}"
        }

        val problems = problems(template)

        assertTrue(problems.any { it.startsWith("$TEMPLATE_ID.fields.displayName:") && "COMPONENT_OWNER" in it }, "problems: $problems")
    }

    @Test
    @DisplayName("a multi-value parameter in a field that is not a list is a problem naming the field")
    fun multiValueInSingleField() {
        assertProblem(
            "fields.baseConfiguration.build.buildTasks",
            withParameter("TASKS", "type" to "select", "options" to listOf("build", "test"), "multiple" to "true"),
        )
    }

    @Test
    @DisplayName("a CRS value with text around {{ NAME }} is a problem")
    fun crsValueWithText() {
        val template =
            withParameter("BS", "type" to "crs-list", "list" to "build-systems").apply {
                at("fields.baseConfiguration.build")["buildSystem"] = "{{ BS }}_X"
            }

        assertProblem("fields.baseConfiguration.build.buildSystem", template)
    }

    @Test
    @DisplayName("a person field with a filter is a problem")
    fun personWithFilter() {
        assertProblem("fields.componentOwner", exampleTemplate().apply { at("fields")["componentOwner"] = "{{ COMPONENT_OWNER | lower }}" })
    }

    @Test
    @DisplayName("a people-list item with text around {{ NAME }} is a problem")
    fun listItemWithText() {
        assertProblem(
            "fields.releaseManager[0]",
            exampleTemplate().apply {
                at("fields")["releaseManager"] =
                    listOf("x-{{ COMPONENT_OWNER }}")
            },
        )
    }

    @Test
    @DisplayName("a CRS value from a parameter of another type or list is a problem")
    fun crsValueWrongParameter() {
        val template =
            withParameter("BS", "type" to "crs-list", "list" to "escrow-generation").apply {
                at("fields.baseConfiguration.build")["buildSystem"] = "{{ BS }}"
            }

        assertProblem("fields.baseConfiguration.build.buildSystem", template)
    }

    @Test
    @DisplayName("a filter other than lower or upper is a problem naming the field")
    fun unknownFilter() {
        assertProblem("fields.name", exampleTemplate().apply { at("fields")["name"] = "{{ CLIENT_CODE | capitalize }}-{{ PLUGIN_CODE }}" })
    }

    @Test
    @DisplayName("a malformed {{ is a problem naming the field")
    fun malformedExpression() {
        assertProblem("fields.name", exampleTemplate().apply { at("fields")["name"] = "{{ CLIENT_CODE | lower }-{{ PLUGIN_CODE }}" })
    }

    @Test
    @DisplayName("a fixed build system not in its list is a problem")
    fun fixedBuildSystemUnknown() {
        assertProblem(
            "fields.baseConfiguration.build.buildSystem",
            exampleTemplate().apply {
                at("fields.baseConfiguration.build")["buildSystem"] =
                    "ANT"
            },
        )
    }

    @Test
    @DisplayName("a fixed escrow generation mode not in its list is a problem")
    fun fixedEscrowModeUnknown() {
        assertProblem(
            "fields.baseConfiguration.escrow.generation",
            exampleTemplate().apply {
                at("fields.baseConfiguration.escrow")["generation"] =
                    "SOMETIMES"
            },
        )
    }

    @Test
    @DisplayName("a fixed choice outside its values is a problem")
    fun fixedChoiceUnknown() {
        val template =
            exampleTemplate().apply {
                @Suppress("UNCHECKED_CAST")
                ((at("fields")["artifactIds"] as List<MutableMap<String, Any>>).single())["mode"] = "SOME"
            }

        assertProblem("fields.artifactIds[0].mode", template)
    }

    @Test
    @DisplayName("a fixed choice taken from a parameter is a problem")
    fun fixedChoiceFromParameter() {
        val template =
            withParameter("MODE", "type" to "select", "options" to listOf("EXPLICIT", "ALL")).apply {
                @Suppress("UNCHECKED_CAST")
                ((at("fields")["artifactIds"] as List<MutableMap<String, Any>>).single())["mode"] = "{{ MODE }}"
            }

        assertProblem("fields.artifactIds[0].mode", template)
    }

    @Test
    @DisplayName("Decision 4: fixed labels and fixed people are not checked on load")
    fun labelsAndPeopleNotCheckedOnLoad() {
        val template =
            exampleTemplate().apply {
                at("fields")["labels"] = listOf("no-such-label")
                at("fields")["securityChampion"] = listOf("nobody-at-all")
            }

        assertEquals(emptyList<String>(), problems(template))
    }

    private fun flatProblems(
        flat: Map<String, String>,
        defaults: Map<String, String> = EXAMPLE_DEFAULTS,
    ): List<String> =
        ComponentProfileParser
            .parse(designExampleProperties() + flat, defaults)
            .entries
            .single { it.id == TEMPLATE_ID }
            .problems

    private fun explicitExternal(): MutableMap<String, Any> =
        exampleTemplate().apply {
            at("classification")["explicit"] = "true"
            at("fields")["releaseManager"] = listOf("{{ COMPONENT_OWNER }}")
            at("fields")["securityChampion"] = listOf("security-lead")
            at("fields.baseConfiguration")["mavenArtifacts"] =
                listOf(linkedMapOf("groupPattern" to "org.example.plugins", "artifactPattern" to "{{ PLUGIN_CODE | lower }}"))
        }

    @Test
    @DisplayName("an explicit, external template with every required field is live")
    fun explicitExternalIsLive() {
        assertEquals(emptyList<String>(), problems(explicitExternal()))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "name", "componentOwner", "baseConfiguration.build.buildSystem", "baseConfiguration.jira.projectKey",
            "baseConfiguration.jira.versionFormat", "baseConfiguration.vcsEntries[0].vcsPath",
            "baseConfiguration.vcsEntries[0].branch", "baseConfiguration.vcsEntries[0].tag",
        ],
    )
    @DisplayName("Decision 4: a field every template requires, set nowhere, is a problem naming it")
    fun requiredForEveryTemplate(path: String) {
        val flat = flatten(exampleTemplate(), TEMPLATE_ID) - "$TEMPLATE_ID.fields.$path"

        val problems = flatProblems(flat, EXAMPLE_DEFAULTS - path)

        assertTrue(problems.any { it.startsWith("$TEMPLATE_ID.fields.$path:") }, "problems: $problems")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["displayName", "releaseManager[0]", "securityChampion[0]"])
    @DisplayName("Decision 4: a field an explicit, external template requires, set nowhere, is a problem naming it")
    fun requiredForExplicitExternal(key: String) {
        val flat = flatten(explicitExternal(), TEMPLATE_ID) - "$TEMPLATE_ID.fields.$key"

        val problems = flatProblems(flat)

        assertTrue(problems.any { it.startsWith("$TEMPLATE_ID.fields.${key.substringBefore('[')}:") }, "problems: $problems")
    }

    @Test
    @DisplayName("displayName, release manager and security champion are not required unless explicit and external")
    fun notRequiredWhenNotExplicit() {
        val flat = flatten(exampleTemplate(), TEMPLATE_ID) - "$TEMPLATE_ID.fields.displayName"

        assertEquals(listOf("$TEMPLATE_ID.parameters.PLUGIN_NAME: no field uses it"), flatProblems(flat))
    }

    @Test
    @DisplayName("a required field filled only by an optional parameter is a problem")
    fun optionalParameterDoesNotFill() {
        val template =
            explicitExternal().apply {
                at("parameters.PLUGIN_NAME")["required"] = "false"
                at("fields")["displayName"] = "{{ PLUGIN_NAME }}"
            }

        assertProblem("fields.displayName", template)
    }

    @Test
    @DisplayName("Decision 4: a required field filled by a component default passes; the Portal's own fallback does not count")
    fun componentDefaultFillsPortalFallbackDoesNot() {
        assertEquals(emptyList<String>(), problems(exampleTemplate()))
        assertProblem("fields.baseConfiguration.jira.versionFormat", exampleTemplate(), defaults = emptyMap())
        assertProblem("fields.baseConfiguration.vcsEntries[0].branch", exampleTemplate(), defaults = emptyMap())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["PROVIDED", "ESCROW_PROVIDED_MANUALLY", "ESCROW_NOT_SUPPORTED", "WHISKEY", "BS2_0"])
    @DisplayName("Decision 4: a build system that needs no VCS needs no VCS path, branch or tag")
    fun noVcsNeeded(buildSystem: String) {
        val flat =
            flatten(exampleTemplate().apply { at("fields.baseConfiguration.build")["buildSystem"] = buildSystem }, TEMPLATE_ID) -
                "$TEMPLATE_ID.fields.baseConfiguration.vcsEntries[0].vcsPath"

        val problems = flatProblems(flat, emptyMap())

        assertTrue(problems.none { it.startsWith("$TEMPLATE_ID.fields.baseConfiguration.vcsEntries") }, "problems: $problems")
    }

    @Test
    @DisplayName("a build system taken from a parameter counts as needing VCS")
    fun buildSystemFromParameterNeedsVcs() {
        val template =
            withParameter("BS", "type" to "crs-list", "list" to "build-systems").apply {
                at("fields.baseConfiguration.build")["buildSystem"] = "{{ BS }}"
                at("fields.baseConfiguration.build")["buildTasks"] = "build"
            }

        assertProblem("fields.baseConfiguration.vcsEntries[0].branch", template, defaults = emptyMap())
    }

    @Test
    @DisplayName("an explicit, external template without a Maven GAV, Docker image or package is a problem")
    fun distributionMissing() {
        val flat =
            flatten(explicitExternal(), TEMPLATE_ID).filterKeys { "mavenArtifacts" !in it } +
                ("$TEMPLATE_ID.fields.baseConfiguration.build.buildTasks" to "{{ PLUGIN_CODE }}")

        assertTrue(flatProblems(flat).any { it.startsWith("$TEMPLATE_ID.fields:") && "Maven" in it }, "problems: ${flatProblems(flat)}")
    }

    @Test
    @DisplayName("WHISKEY needs no distribution")
    fun whiskeyNeedsNoDistribution() {
        val flat =
            flatten(explicitExternal().apply { at("fields.baseConfiguration.build")["buildSystem"] = "WHISKEY" }, TEMPLATE_ID)
                .filterKeys { "mavenArtifacts" !in it } +
                ("$TEMPLATE_ID.fields.baseConfiguration.build.buildTasks" to "{{ PLUGIN_CODE }}")

        assertEquals(emptyList<String>(), flatProblems(flat))
    }

    @Test
    @DisplayName("an overridable path the template does not set is a problem naming it")
    fun overridableNotSet() {
        val template =
            exampleTemplate().apply {
                put("overridable", listOf("baseConfiguration.vcsEntries[0].vcsPath", "baseConfiguration.dockerImages[0].imageName"))
            }

        assertProblem("overridable[1]", template)
    }

    @Test
    @DisplayName("a fixed value breaking one of the template's own rules is a problem")
    fun fixedValueBreaksRule() {
        val template =
            exampleTemplate().apply {
                put(
                    "rules",
                    linkedMapOf(
                        "baseConfiguration.jira.projectKey" to linkedMapOf("pattern" to "CUST.*", "message" to "Use a CUST project."),
                    ),
                )
            }

        assertProblem("rules.baseConfiguration.jira.projectKey", template)
    }

    @Test
    @DisplayName("a rule on a field built from parameters is not checked on load")
    fun ruleOnParameterFieldNotCheckedOnLoad() {
        val template =
            exampleTemplate().apply {
                put("rules", linkedMapOf("name" to linkedMapOf("pattern" to "never", "message" to "m")))
            }

        assertEquals(emptyList<String>(), problems(template))
    }

    @Test
    @DisplayName("solution: true without explicit and external is a problem")
    fun solutionNeedsExplicitExternal() {
        assertProblem("classification.solution", exampleTemplate().apply { at("classification")["solution"] = "true" })
    }

    @Test
    @DisplayName("several problems in one template are all reported, each naming its parameter or field")
    fun severalProblems() {
        val template =
            exampleTemplate().apply {
                at("parameters.PLUGIN_CODE")["pattern"] = "[A-Z"
                at("fields")["clientCode"] = "{{ CLIENT }}"
                at("fields.baseConfiguration.escrow")["generation"] = "SOMETIMES"
            }

        val problems = problems(template)

        listOf("parameters.PLUGIN_CODE.pattern", "fields.clientCode", "fields.baseConfiguration.escrow.generation").forEach { key ->
            assertTrue(problems.any { it.startsWith("$TEMPLATE_ID.$key:") }, "expected a problem on '$key', got $problems")
        }
    }

    @Test
    @DisplayName("a failed template does not make the load unusable")
    fun failedTemplateKeepsLoadUsable() {
        val load = load(exampleTemplate().apply { remove("title") })

        assertTrue(load.usable)
        assertEquals(emptyList<ComponentTemplate>(), load.templates)
    }
}
