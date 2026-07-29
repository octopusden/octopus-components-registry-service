package org.octopusden.octopus.validation.resolvers.teamcity.configuration

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl.DefaultBuildConfigurationResolver
import org.octopusden.octopus.validation.teamcity.GRADLE_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.MAVEN_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.RELEASE_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.TestTemplateCatalog
import org.octopusden.octopus.validation.teamcity.buildConfig
import org.octopusden.octopus.validation.teamcity.tcProject
import kotlin.test.assertEquals

class DefaultBuildConfigurationResolverTest {
    private val resolver = DefaultBuildConfigurationResolver(TestTemplateCatalog)

    @Test
    @DisplayName("attachedToBuildTemplate returns only configs inheriting from a build template")
    fun `attached returns gradle and maven configs only`() {
        val gradleConfig = buildConfig("Gradle", templateIds = setOf(GRADLE_TEMPLATE_ID))
        val mavenConfig = buildConfig("Maven", templateIds = setOf(MAVEN_TEMPLATE_ID))
        val releaseConfig = buildConfig("Release", templateIds = setOf(RELEASE_TEMPLATE_ID))
        val plainConfig = buildConfig("Plain")
        val project = tcProject(configs = listOf(gradleConfig, mavenConfig, releaseConfig, plainConfig))

        val attached = resolver.attachedToBuildTemplate(project)

        assertEquals(setOf("Gradle", "Maven"), attached.map { it.id }.toSet())
    }

    @Test
    @DisplayName("notAttachedToBuildTemplate excludes both build-template and release-family configs")
    fun `not-attached excludes build templates and release family`() {
        val gradleConfig = buildConfig("Gradle", templateIds = setOf(GRADLE_TEMPLATE_ID))
        val releaseConfig = buildConfig("Release", templateIds = setOf(RELEASE_TEMPLATE_ID))
        val plainConfig = buildConfig("Plain")
        val project = tcProject(configs = listOf(gradleConfig, releaseConfig, plainConfig))

        val notAttached = resolver.notAttachedToBuildTemplate(project)

        assertEquals(listOf("Plain"), notAttached.map { it.id })
    }
}
