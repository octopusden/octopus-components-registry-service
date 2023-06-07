package org.octopusden.octopus.components.registry.dsl.test

import org.octopusden.octopus.components.registry.dsl.script.ComponentsRegistryScriptRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ComponentsRegistryScriptRunnerTest {
    @Test
    fun testLoadingSingleFile() {
        val dslFilePath = Paths.get(ComponentsRegistryScriptRunnerTest::class.java.getResource("/InfrastructureComponents.kts").toURI())
        val registry = ComponentsRegistryScriptRunner.loadDSLFile(dslFilePath)
        assertTrue("escrow-generator" in registry.map { it.name })
    }

    @Test
    fun testLoading() {
        val dslBasePath = Paths.get(ComponentsRegistryScriptRunnerTest::class.java.getResource("/multiply-files/File1.kts").toURI()).parent
        val registry = ComponentsRegistryScriptRunner.loadDSL(dslBasePath)
        assertTrue("escrow-generator" in registry.map { it.name })
        assertTrue("releng" in registry.map { it.name })
    }
}