package org.octopusden.octopus.validation.resolvers.teamcity.step

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.JavaHomeReferenceResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.impl.JavaVersionResolver
import org.octopusden.octopus.validation.teamcity.buildStep
import org.octopusden.octopus.validation.teamcity.params
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaHomeReferenceResolverTest {
    private val resolver = JavaHomeReferenceResolver(JavaVersionResolver())

    @Test
    @DisplayName("gradle: reports env.JAVA_HOME when target.jdk.home references it directly")
    fun `gradle direct env java home`() {
        val step = buildStep(
            "s",
            StepType.GRADLE,
            parameters = params("target.jdk.home" to "%env.JAVA_HOME%", "env.JAVA_HOME" to "/usr/lib/jvm/java-21"),
        )
        assertTrue("env.JAVA_HOME" in resolver.javaHomeReferences(step))
    }

    @Test
    @DisplayName("gradle: reports env.JAVA_HOME reached through an intermediate parameter")
    fun `gradle indirect env java home`() {
        val step = buildStep(
            "s",
            StepType.GRADLE,
            parameters = params(
                "target.jdk.home" to "%custom.jdk%",
                "custom.jdk" to "%env.JAVA_HOME%",
                "env.JAVA_HOME" to "/usr/lib/jvm/java-17",
            ),
        )
        val refs = resolver.javaHomeReferences(step)
        assertTrue("env.JAVA_HOME" in refs)
        assertTrue("custom.jdk" in refs)
    }

    @Test
    @DisplayName("gradle: a direct JDK reference does not report env.JAVA_HOME")
    fun `gradle direct jdk not env`() {
        val step = buildStep(
            "s",
            StepType.GRADLE,
            parameters = params("target.jdk.home" to "%env.JDK_17%", "env.JDK_17" to "/usr/lib/jvm/java-17"),
        )
        val refs = resolver.javaHomeReferences(step)
        assertTrue("env.JDK_17" in refs)
        assertFalse("env.JAVA_HOME" in refs)
    }

    @Test
    @DisplayName("a reference to a missing env.JAVA_HOME is still reported (agent env var, not a config param)")
    fun `missing env java home still reported`() {
        val step = buildStep("s", StepType.GRADLE, parameters = params("target.jdk.home" to "%env.JAVA_HOME%"))
        assertTrue("env.JAVA_HOME" in resolver.javaHomeReferences(step))
    }

    @Test
    @DisplayName("command-line: reports references only for tokens that resolve to a Java version")
    fun `command line java token references`() {
        val step = buildStep(
            "s",
            StepType.COMMAND_LINE,
            parameters = params(
                "script.content" to "%java.home.ref%/bin/java --version",
                "java.home.ref" to "%env.JAVA_HOME%",
                "env.JAVA_HOME" to "/usr/lib/jvm/java-17",
            ),
        )
        assertTrue("env.JAVA_HOME" in resolver.javaHomeReferences(step))
    }

    @Test
    @DisplayName("OTHER step type has no java-home source")
    fun `other type empty`() {
        assertEquals(emptySet(), resolver.javaHomeReferences(buildStep("s", StepType.OTHER)))
    }
}
