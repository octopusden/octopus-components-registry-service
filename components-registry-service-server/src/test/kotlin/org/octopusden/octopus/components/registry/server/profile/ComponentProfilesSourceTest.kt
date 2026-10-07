package org.octopusden.octopus.components.registry.server.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

class ComponentProfilesSourceTest {
    private fun environment(vararg resources: String): StandardEnvironment {
        val environment = StandardEnvironment()
        resources.forEach { resource ->
            YamlPropertySourceLoader()
                .load(resource, ClassPathResource("component-profiles/$resource"))
                .forEach { environment.propertySources.addFirst(it) }
        }
        return environment
    }

    @Test
    @DisplayName("YAML arrives as flat keys relative to the prefix, numbers and booleans as strings")
    fun flattensYaml() {
        val properties = ComponentProfilesSource(environment("base.yml")).read()

        assertEquals("regular", properties["solution.kind"])
        assertEquals("30", properties["solution.order"])
        assertEquals("true", properties["solution.classification.solution"])
        assertEquals("^[a-z][a-z0-9-]*-solution(-[a-z0-9-]+)?$", properties["solution.rules.name.pattern"])
        assertTrue(properties.keys.none { it.startsWith("components-registry.") || it.contains("field-config") }, "$properties")
    }

    @Test
    @DisplayName("a dotted, indexed rule path stays one key")
    fun keepsDottedRulePath() {
        val properties = ComponentProfilesSource(environment("base.yml")).read()

        assertEquals("^pay-.*$", properties["solution.rules.baseConfiguration.mavenArtifacts[0].artifactPattern.pattern"])
    }

    @Test
    @DisplayName("a later file overrides the base key by key and can add a profile")
    fun overridesKeyByKey() {
        val properties = ComponentProfilesSource(environment("base.yml", "override.yml")).read()

        assertEquals("Solution (QA)", properties["solution.title"])
        assertEquals("30", properties["solution.order"])
        assertEquals("regular", properties["regular-internal.kind"])
    }

    @Test
    @DisplayName("the source output parses into live profiles")
    fun parsesEndToEnd() {
        val result = ComponentProfileParser.parse(ComponentProfilesSource(environment("base.yml", "override.yml")).read())

        assertTrue(result.usable, "${result.entries}")
        assertEquals(listOf("regular-internal", "solution"), result.profiles.map { it.id })
    }

    @Test
    @DisplayName("an absent subtree reads as an empty map")
    fun absentSubtree() {
        assertEquals(emptyMap<String, String>(), ComponentProfilesSource(StandardEnvironment()).read())
    }

    @Test
    @DisplayName("ids arrive exactly as written, so the parser can reject them")
    fun idsAsWritten() {
        val properties = ComponentProfilesSource(environment("odd-ids.yml")).read()

        assertEquals("regular", properties["regular_external.kind"])
        assertEquals("regular", properties["Regular-External.kind"])
    }

    @Test
    @DisplayName("dotted and indexed rule paths written without brackets arrive as one path")
    fun unbracketedRulePaths() {
        val properties = ComponentProfilesSource(environment("odd-ids.yml")).read()

        assertEquals("^[A-Z]+$", properties["internal.rules.baseConfiguration.jira.projectKey.pattern"])
        assertEquals("Starts with org.", properties["internal.rules.artifactIds[0].groupPattern.message"])
    }

    @Test
    @DisplayName("typed numbers and booleans from a non-YAML source arrive as strings")
    fun typedValuesAsStrings() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "typed",
                mapOf(
                    "components-registry.component-profiles.internal.order" to 20,
                    "components-registry.component-profiles.internal.classification.external" to false,
                ),
            ),
        )

        val properties = ComponentProfilesSource(environment).read()

        assertEquals("20", properties["internal.order"])
        assertEquals("false", properties["internal.classification.external"])
    }

    @Test
    @DisplayName("ids that relaxed binding would treat as one keep their own values when Boot's relaxed layer is attached")
    fun relaxedAliasesKeptApart() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "aliases",
                mapOf(
                    "components-registry.component-profiles.regular-internal.title" to "Internal dashed",
                    "components-registry.component-profiles.regularinternal.title" to "Internal undashed",
                ),
            ),
        )
        ConfigurationPropertySources.attach(environment)

        val properties = ComponentProfilesSource(environment).read()

        assertEquals("Internal dashed", properties["regular-internal.title"])
        assertEquals("Internal undashed", properties["regularinternal.title"])
    }

    @Test
    @DisplayName("a higher-precedence source wins per key, read from the source itself")
    fun precedenceFromSources() {
        val environment = environment("base.yml", "override.yml")
        ConfigurationPropertySources.attach(environment)

        val properties = ComponentProfilesSource(environment).read()

        assertEquals("Solution (QA)", properties["solution.title"])
        assertEquals("30", properties["solution.order"])
    }

    @Test
    @DisplayName("a placeholder in a value is resolved against the environment")
    fun placeholderResolved() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "placeholders",
                mapOf("org-name" to "Example", "components-registry.component-profiles.internal.title" to "\${org-name} internal"),
            ),
        )

        assertEquals("Example internal", ComponentProfilesSource(environment).read()["internal.title"])
    }
}
