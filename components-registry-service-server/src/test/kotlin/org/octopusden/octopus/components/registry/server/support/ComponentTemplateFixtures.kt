package org.octopusden.octopus.components.registry.server.support

import org.octopusden.octopus.components.registry.server.model.ComponentTemplate
import org.octopusden.octopus.components.registry.server.support.designExampleProperties
import org.octopusden.octopus.components.registry.server.util.ComponentProfileParser

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
