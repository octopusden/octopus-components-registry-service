package org.octopusden.octopus.validation.teamcity.resolvers.step

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.dto.teamcity.MavenVersion
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.CommandLineBuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.GradleBuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.MavenBuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.impl.JavaVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.impl.MavenVersionResolver
import org.octopusden.octopus.validation.teamcity.buildStep
import org.octopusden.octopus.validation.teamcity.params
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildStepToolVersionResolverTest {
    private val javaVersionResolver = JavaVersionResolver()
    private val mavenVersionResolver = MavenVersionResolver()

    @Test
    @DisplayName("MavenBuildStepToolVersionResolver reads maven.path and target.jdk.home")
    fun `maven step resolves both maven and java versions`() {
        val resolver = MavenBuildStepToolVersionResolver(javaVersionResolver, mavenVersionResolver)
        val step = buildStep(
            "s1",
            StepType.MAVEN,
            parameters = params("maven.path" to "env.MAVEN_3.6.3", "target.jdk.home" to "env.JDK_21"),
        )

        assertEquals(setOf(MavenVersion("3.6.3"), JavaVersion("21")), resolver.resolve(step))
        assertTrue(resolver.supports(StepType.MAVEN))
    }

    @Test
    @DisplayName("MavenBuildStepToolVersionResolver follows a %param% reference chain")
    fun `maven step resolves through a reference chain`() {
        val resolver = MavenBuildStepToolVersionResolver(javaVersionResolver, mavenVersionResolver)
        val step = buildStep(
            "s1",
            StepType.MAVEN,
            parameters = params("maven.path" to "%teamcity.tool.maven%", "teamcity.tool.maven" to "env.MAVEN_LATEST"),
        )

        assertEquals(setOf(MavenVersion("LATEST")), resolver.resolve(step))
    }

    @Test
    @DisplayName("GradleBuildStepToolVersionResolver reads target.jdk.home only")
    fun `gradle step resolves only java version`() {
        val resolver = GradleBuildStepToolVersionResolver(javaVersionResolver)
        val step = buildStep("s1", StepType.GRADLE, parameters = params("target.jdk.home" to "env.JDK_21_x64"))

        assertEquals(setOf(JavaVersion("21")), resolver.resolve(step))
        assertTrue(resolver.supports(StepType.GRADLE))
    }

    @Test
    @DisplayName("CommandLineBuildStepToolVersionResolver splits script.content and tries both resolvers on each token")
    fun `command line step tries both resolvers per token`() {
        val resolver = CommandLineBuildStepToolVersionResolver(javaVersionResolver, mavenVersionResolver)
        val step = buildStep(
            "s1",
            StepType.COMMAND_LINE,
            parameters = params("script.content" to "run env.MAVEN_3.6.3 with env.JDK_17 please"),
        )

        assertEquals(setOf(MavenVersion("3.6.3"), JavaVersion("17")), resolver.resolve(step))
    }

    @Test
    @DisplayName("CommandLineBuildStepToolVersionResolver resolves a %param% reference within a token")
    fun `command line step resolves a token that is itself a reference`() {
        val resolver = CommandLineBuildStepToolVersionResolver(javaVersionResolver, mavenVersionResolver)
        val step = buildStep(
            "s1",
            StepType.COMMAND_LINE,
            parameters = params("script.content" to "%env.JAVA_HOME%", "env.JAVA_HOME" to "env.JDK_1_8_x64"),
        )

        assertEquals(setOf(JavaVersion("1.8")), resolver.resolve(step))
    }

    @Test
    @DisplayName("CommandLineBuildStepToolVersionResolver supports both COMMAND_LINE and IN_CONTAINER (D-assumption, see TD-016)")
    fun `command line resolver supports command line and in container`() {
        val resolver = CommandLineBuildStepToolVersionResolver(javaVersionResolver, mavenVersionResolver)

        assertTrue(resolver.supports(StepType.COMMAND_LINE))
        assertTrue(resolver.supports(StepType.IN_CONTAINER))
    }

    @Test
    @DisplayName("DefaultBuildStepToolVersionResolver.standard() dispatches every step to its runner-specific resolver")
    fun `standard dispatcher wires all runner types`() {
        val dispatcher = DefaultBuildStepToolVersionResolver.standard()

        val mavenStep = buildStep("m", StepType.MAVEN, parameters = params("maven.path" to "env.MAVEN_3.6.3"))
        val gradleStep = buildStep("g", StepType.GRADLE, parameters = params("target.jdk.home" to "env.JDK_21"))
        val commandLineStep = buildStep("c", StepType.COMMAND_LINE, parameters = params("script.content" to "env.JDK_25"))
        val inContainerStep = buildStep("i", StepType.IN_CONTAINER, parameters = params("script.content" to "env.JDK_25"))
        val otherStep = buildStep("o", StepType.OTHER)

        assertEquals(setOf(MavenVersion("3.6.3")), dispatcher.resolve(mavenStep))
        assertEquals(setOf(JavaVersion("21")), dispatcher.resolve(gradleStep))
        assertEquals(setOf(JavaVersion("25")), dispatcher.resolve(commandLineStep))
        assertEquals(setOf(JavaVersion("25")), dispatcher.resolve(inContainerStep))
        assertEquals(emptySet(), dispatcher.resolve(otherStep))
        assertTrue(!dispatcher.supports(StepType.OTHER))
    }
}
