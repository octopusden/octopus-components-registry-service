package org.octopusden.octopus.components.registry.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfilesException
import org.octopusden.octopus.components.registry.server.util.ComponentProfileParser
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

    @ParameterizedTest
    @ValueSource(strings = ["root-empty-map.yml", "root-empty-list.yml", "root-null.yml"])
    @DisplayName("a subtree written empty reads as a blank root value and loads as no profiles")
    fun emptySubtree(resource: String) {
        val properties = ComponentProfilesSource(environment(resource)).read()

        assertEquals(mapOf("" to ""), properties)
        assertEquals(emptyList<Any>(), ComponentProfileCatalog { properties }.profiles())
    }

    @Test
    @DisplayName("a subtree written as a plain value stops startup, naming the value")
    fun scalarSubtreeAtStartup() {
        val source = ComponentProfilesSource(environment("root-scalar.yml"))

        val error = assertThrows(ComponentProfilesException::class.java) { ComponentProfileCatalog(source::read) }

        assertTrue(requireNotNull(error.message).contains("components-registry.component-profiles: 'broken' is not a map of profiles"))
    }

    @Test
    @DisplayName("a subtree written as a list stops startup, the list entry being no profile id")
    fun listSubtreeAtStartup() {
        val source = ComponentProfilesSource(environment("root-list.yml"))

        val error = assertThrows(ComponentProfilesException::class.java) { ComponentProfileCatalog(source::read) }

        assertTrue(
            requireNotNull(error.message).contains("components-registry.component-profiles: a list is not a map of profiles"),
            error.message,
        )
    }

    @Test
    @DisplayName("a list of template-like entries over valid profiles from another file stops startup")
    fun templateListOverProfilesAtStartup() {
        val source = ComponentProfilesSource(environment("base.yml", "root-template-list.yml"))

        val error = assertThrows(ComponentProfilesException::class.java) { ComponentProfileCatalog(source::read) }

        assertTrue(
            requireNotNull(error.message).contains("components-registry.component-profiles: a list is not a map of profiles"),
            error.message,
        )
    }

    @Test
    @DisplayName("a list of template-like entries added on reload is not applied, nor is a title change beside it")
    fun templateListOverProfilesOnReload() {
        val environment = environment("base.yml")
        val catalog = ComponentProfileCatalog(ComponentProfilesSource(environment)::read)
        val before = catalog.profiles()
        YamlPropertySourceLoader()
            .load("root-template-list.yml", ClassPathResource("component-profiles/root-template-list.yml"))
            .forEach { environment.propertySources.addFirst(it) }
        environment.propertySources.addFirst(
            MapPropertySource("title-change", mapOf("components-registry.component-profiles.solution.title" to "Changed")),
        )

        val load = catalog.reload()

        assertFalse(load.usable)
        assertEquals(before, catalog.profiles())
    }

    @Test
    @DisplayName("a subtree turned into a plain value is not applied on reload; the profiles in use are kept")
    fun scalarSubtreeOnReload() {
        val environment = environment("base.yml")
        val catalog = ComponentProfileCatalog(ComponentProfilesSource(environment)::read)
        val before = catalog.profiles()
        YamlPropertySourceLoader()
            .load("root-scalar.yml", ClassPathResource("component-profiles/root-scalar.yml"))
            .forEach { environment.propertySources.addFirst(it) }

        val load = catalog.reload()

        assertFalse(load.usable)
        assertEquals(before, catalog.profiles())
    }

    @Test
    @DisplayName("rules written empty or with no value load as a profile without rules")
    fun emptyRules() {
        val result = ComponentProfileParser.parse(ComponentProfilesSource(environment("empty-rules.yml")).read())

        assertTrue(result.usable, "${result.problems} ${result.entries}")
        assertEquals(listOf(emptyList<Any>(), emptyList<Any>()), result.profiles.map { it.rules })
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
