package org.octopusden.octopus.components.registry.server.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.octopusden.octopus.components.registry.server.dto.v4.ArtifactIdRequest
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryRequest
import org.octopusden.octopus.components.registry.server.util.ComponentProfileParser
import org.octopusden.octopus.components.registry.server.support.designExampleProperties

/** A template as the parser produces it, failing the test when it is not live. */
fun parsedTemplate(
    template: Map<String, Any> = exampleTemplate(),
    defaults: Map<String, String> = EXAMPLE_DEFAULTS,
): ComponentTemplate {
    val load = ComponentProfileParser.parse(designExampleProperties() + flatten(template, TEMPLATE_ID), defaults)
    return requireNotNull(load.templates.singleOrNull()) { "not live: ${load.entries.single { it.id == TEMPLATE_ID }.problems}" }
}

/** The design example's dry-run values, the owner given. */
val EXAMPLE_VALUES =
    mapOf(
        "CLIENT_CODE" to listOf("ACME"),
        "PLUGIN_CODE" to listOf("CORE"),
        "PLUGIN_NAME" to listOf("Core API"),
        "COMPONENT_OWNER" to listOf("jdoe"),
    )

class TemplateRendererTest {
    private fun render(
        template: ComponentTemplate = parsedTemplate(),
        values: Map<String, List<String>> = EXAMPLE_VALUES,
        overrides: Map<String, List<String>> = emptyMap(),
        defaults: Map<String, String> = EXAMPLE_DEFAULTS,
    ) = TemplateRenderer.render(template, values, overrides, defaults, jiraTaskKey = "PLUGINS-12", changeComment = "Onboarding ACME")

    @Test
    @DisplayName("Decision 6: the design example renders to the expected request")
    fun designExampleRequest() {
        val request = render().request

        assertEquals("acme-plugin-core", request.name)
        assertEquals("CORE API for ACME", request.displayName)
        assertEquals("jdoe", request.componentOwner)
        assertEquals("ACME", request.clientCode)
        assertEquals(setOf("plugin"), request.labels)
        assertEquals(
            listOf(ArtifactIdRequest(groupPattern = "org.example.plugins.acme", mode = "EXPLICIT", artifactTokens = listOf("core"))),
            request.artifactIds,
        )
        val base = requireNotNull(request.baseConfiguration)
        assertEquals("GRADLE", base.build?.buildSystem)
        assertEquals("build", base.build?.buildTasks)
        assertEquals(
            listOf(
                VcsEntryRequest(vcsPath = "ssh://git@git.example.com/clients/acme/plugin.git", branch = "main", tag = "\$module-\$version"),
            ),
            base.vcsEntries,
        )
        assertEquals("PLUGINS", base.jira?.projectKey)
        assertEquals("acme-plugin-core", base.jira?.versionPrefix)
        assertEquals("\$versionPrefix-\$baseVersionFormat", base.jira?.versionFormat)
        assertEquals("UNSUPPORTED", base.escrow?.generation)
    }

    @Test
    @DisplayName("Decision 6: sources name the parameters each set field came from; fixed and defaulted fields have none")
    fun designExampleSources() {
        val sources = render().sources

        assertEquals(setOf("CLIENT_CODE", "PLUGIN_CODE"), sources["name"])
        assertEquals(setOf("PLUGIN_NAME", "CLIENT_CODE"), sources["displayName"])
        assertEquals(setOf("COMPONENT_OWNER"), sources["componentOwner"])
        assertEquals(emptySet<String>(), sources["labels"])
        assertEquals(emptySet<String>(), sources["baseConfiguration.jira.projectKey"])
        assertEquals(emptySet<String>(), sources["baseConfiguration.vcsEntries[0].branch"])
    }

    @Test
    @DisplayName("R1: expressions are replaced, converted by lower or upper, and the text around them kept")
    fun r1FreeText() {
        assertEquals("acme-plugin-core", render(values = EXAMPLE_VALUES + ("CLIENT_CODE" to listOf("Acme"))).request.name)
    }

    @Test
    @DisplayName("R2: an empty optional parameter is replaced by nothing; a free-text field that ends up empty is unset")
    fun r2EmptyValues() {
        val template =
            parsedTemplate(
                exampleTemplate().apply {
                    at("parameters")["SUFFIX"] = linkedMapOf("label" to "Suffix", "type" to "text", "required" to "false")
                    at("fields")["name"] = "{{ CLIENT_CODE | lower }}-plugin{{ SUFFIX }}"
                    at("fields.baseConfiguration.build")["buildTasks"] = "{{ SUFFIX }}"
                },
            )

        val rendered = render(template, EXAMPLE_VALUES + ("SUFFIX" to emptyList()))

        assertEquals("acme-plugin", rendered.request.name)
        assertNull(
            rendered.request.baseConfiguration
                ?.build
                ?.buildTasks,
        )
        assertFalse("baseConfiguration.build.buildTasks" in rendered.sources)
    }

    @Test
    @DisplayName("R3: a CRS value or person field set to {{ NAME }} takes the value as it is")
    fun r3WholeValues() {
        val template =
            parsedTemplate(
                exampleTemplate().apply {
                    at("parameters")["BS"] = linkedMapOf("label" to "Build system", "type" to "crs-list", "list" to "build-systems")
                    at("fields.baseConfiguration.build")["buildSystem"] = "{{ BS }}"
                },
            )

        val rendered = render(template, EXAMPLE_VALUES + ("BS" to listOf("MAVEN")))

        assertEquals(
            "MAVEN",
            rendered.request.baseConfiguration
                ?.build
                ?.buildSystem,
        )
        assertEquals(setOf("BS"), rendered.sources["baseConfiguration.build.buildSystem"])
    }

    @Test
    @DisplayName("R4: a free-text list renders each item; an empty item is dropped; a multi-value parameter becomes one item per value")
    fun r4FreeTextLists() {
        val template =
            parsedTemplate(
                exampleTemplate().apply {
                    at("parameters")["MODULES"] =
                        linkedMapOf(
                            "label" to "Modules",
                            "type" to "select",
                            "options" to listOf("api", "ui"),
                            "multiple" to "true",
                            "required" to "false",
                        )
                    at("parameters")["SUFFIX"] = linkedMapOf("label" to "Suffix", "type" to "text", "required" to "false")
                    @Suppress("UNCHECKED_CAST")
                    ((at("fields")["artifactIds"] as List<MutableMap<String, Any>>).single())["artifactTokens"] =
                        listOf("{{ PLUGIN_CODE | lower }}", "{{ MODULES }}", "{{ SUFFIX }}")
                },
            )

        val rendered = render(template, EXAMPLE_VALUES + ("MODULES" to listOf("api", "ui")) + ("SUFFIX" to emptyList()))

        assertEquals(
            listOf("core", "api", "ui"),
            rendered.request.artifactIds
                .single()
                .artifactTokens,
        )
        assertEquals(setOf("PLUGIN_CODE", "MODULES", "SUFFIX"), rendered.sources["artifactIds[0].artifactTokens"])
    }

    @Test
    @DisplayName("R5: a CRS or people list keeps the template's order, a parameter's values in its place, without duplicates")
    fun r5Lists() {
        val template =
            parsedTemplate(
                exampleTemplate().apply {
                    at("parameters")["EXTRA"] =
                        linkedMapOf("label" to "Labels", "type" to "crs-list", "list" to "labels", "required" to "false")
                    at("fields")["labels"] = listOf("plugin", "{{ EXTRA }}")
                    at("fields")["releaseManager"] = listOf("lead", "{{ COMPONENT_OWNER }}")
                },
            )

        val request = render(template, EXAMPLE_VALUES + ("EXTRA" to listOf("plugin", "ui"))).request

        assertEquals(listOf("plugin", "ui"), request.labels.toList())
        assertEquals(listOf("lead", "jdoe"), request.releaseManager)
    }

    @Test
    @DisplayName("R6: the classification is always the template's")
    fun r6Classification() {
        val request = render().request

        assertEquals(true, request.distributionExternal)
        assertEquals(false, request.distributionExplicit)
        assertEquals(false, request.solution)
    }

    @Test
    @DisplayName("R7: a field still unset takes its default with no source; a field the template sets keeps its value")
    fun r7Defaults() {
        val rendered = render(defaults = EXAMPLE_DEFAULTS + ("baseConfiguration.jira.projectKey" to "OTHER"))

        assertEquals(
            "\$versionPrefix-\$baseVersionFormat",
            rendered.request.baseConfiguration
                ?.jira
                ?.versionFormat,
        )
        assertEquals(emptySet<String>(), rendered.sources["baseConfiguration.jira.versionFormat"])
        assertEquals(
            "PLUGINS",
            rendered.request.baseConfiguration
                ?.jira
                ?.projectKey,
        )
    }

    @Test
    @DisplayName("R7: copyright is defaulted only for an explicit, external template")
    fun r7Copyright() {
        val defaults = EXAMPLE_DEFAULTS + ("copyright" to "Example Corp")

        assertNull(render(defaults = defaults).request.copyright)
        val explicit = parsedTemplate(exampleTemplate().apply { at("classification")["explicit"] = "true" }.withExplicitExternalFields())
        assertEquals("Example Corp", render(explicit, defaults = defaults).request.copyright)
    }

    @Test
    @DisplayName("R7: VCS tag and branch are defaulted only when the build system needs VCS")
    fun r7VcsOnlyWhenNeeded() {
        val template =
            parsedTemplate(
                exampleTemplate().apply {
                    at("fields.baseConfiguration.build")["buildSystem"] = "PROVIDED"
                    at("fields.baseConfiguration").remove("vcsEntries")
                    remove("overridable")
                },
            )

        assertEquals(null, render(template).request.baseConfiguration?.vcsEntries)
    }

    @Test
    @DisplayName("R8: an override replaces the rendered value of its field, and nothing else")
    fun r8Override() {
        val path = "baseConfiguration.vcsEntries[0].vcsPath"

        val rendered = render(overrides = mapOf(path to listOf("ssh://git@git.example.com/clients/acme/plugin-v2.git")))

        assertEquals(
            "ssh://git@git.example.com/clients/acme/plugin-v2.git",
            rendered.request.baseConfiguration
                ?.vcsEntries
                ?.single()
                ?.vcsPath,
        )
        assertEquals(setOf(path), rendered.overridden)
        assertEquals(emptySet<String>(), rendered.sources[path], "an overridden value came from no parameter")
        assertEquals(render().request.copy(baseConfiguration = null), rendered.request.copy(baseConfiguration = null))
    }

    @Test
    @DisplayName("R8: an override on a path the template does not list as overridable is refused")
    fun r8NotOverridable() {
        assertThrows<IllegalArgumentException> { render(overrides = mapOf("name" to listOf("other"))) }
    }

    @Test
    @DisplayName("the same input twice renders the same output")
    fun deterministic() {
        assertEquals(render(), render())
    }

    @Test
    @DisplayName("the Jira task key and comment are carried; no profile is named")
    fun changeMetadata() {
        val request = render().request

        assertEquals("PLUGINS-12", request.jiraTaskKey)
        assertEquals("Onboarding ACME", request.changeComment)
        assertNull(request.profile)
    }
}

/** What an explicit, external example needs besides the classification: people and a distribution. */
fun MutableMap<String, Any>.withExplicitExternalFields(): MutableMap<String, Any> =
    apply {
        at("fields")["releaseManager"] = listOf("{{ COMPONENT_OWNER }}")
        at("fields")["securityChampion"] = listOf("security-lead")
        at("fields.baseConfiguration")["mavenArtifacts"] =
            listOf(linkedMapOf("groupPattern" to "org.example.plugins", "artifactPattern" to "{{ PLUGIN_CODE | lower }}"))
    }
