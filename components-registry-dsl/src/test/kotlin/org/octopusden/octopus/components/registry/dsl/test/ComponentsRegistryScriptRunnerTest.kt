package org.octopusden.octopus.components.registry.dsl.test

import org.octopusden.octopus.components.registry.dsl.script.ComponentsRegistryScriptRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.dsl.PT_C
import org.octopusden.octopus.components.registry.dsl.PT_D
import org.octopusden.octopus.components.registry.dsl.PT_D_DB
import org.octopusden.octopus.components.registry.dsl.PT_K
import java.nio.file.Paths

class ComponentsRegistryScriptRunnerTest {

    companion object {
        private val PRODUCT_TYPE = mapOf(PT_C to "PT_C", PT_K to "PT_K", PT_D to "PT_D", PT_D_DB to "PT_D_DB")
    }

    @Test
    fun testLoadingSingleFile() {
        val dslFilePath = Paths.get(ComponentsRegistryScriptRunnerTest::class.java.getResource("/InfrastructureComponents.kts").toURI())
        val registry = ComponentsRegistryScriptRunner.loadDSLFile(dslFilePath, PRODUCT_TYPE)
        assertTrue("escrow-generator" in registry.map { it.name })
    }

    @Test
    fun testLoading() {
        val dslBasePath = Paths.get(ComponentsRegistryScriptRunnerTest::class.java.getResource("/multiply-files/File1.kts").toURI()).parent
        val registry = ComponentsRegistryScriptRunner.loadDSL(dslBasePath, PRODUCT_TYPE)
        assertTrue("escrow-generator" in registry.map { it.name })
        assertTrue("releng" in registry.map { it.name })
    }
}