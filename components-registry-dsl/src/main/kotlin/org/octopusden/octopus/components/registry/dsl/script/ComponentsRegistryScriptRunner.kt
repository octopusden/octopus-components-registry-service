package org.octopusden.octopus.components.registry.dsl.script

import org.octopusden.octopus.components.registry.api.Component
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import org.jetbrains.kotlin.script.jsr223.KotlinJsr223JvmLocalScriptEngineFactory
import kotlin.script.experimental.jsr223.KotlinJsr223DefaultScriptEngineFactory
import kotlin.collections.ArrayList

object ComponentsRegistryScriptRunner {
    private val logger = Logger.getLogger(ComponentsRegistryScriptRunner::class.java.canonicalName)
    private val currentRegistry = ArrayList<Component>()

    init {
        setIdeaIoUseFallback()
        if (System.getProperty("kotlin.script.classpath").isNullOrEmpty()) {
            logger.info("cr.dsl.class.path = ${System.getProperty("cr.dsl.class.path")}")
            logger.info("Setting kotlin.script.classpath= ${System.getProperty("cr.dsl.class.path", System.getProperty("java.class.path"))}")
            System.setProperty("kotlin.script.classpath", System.getProperty("cr.dsl.class.path", System.getProperty("java.class.path")))
        }
    }

    /**
     * Load  kotlin DSL Components Registry.
     * For boot jar based application System property 'kotlin.script.classpath' has to be set to DSL libraries before call.
     */
    fun loadDSL(basePath: Path): Collection<Component> {
        val registry = ArrayList<Component>()
        Files.list(basePath).filter { path -> path.fileName.toString().endsWith(".kts") }.forEach { path ->
            val loadedComponents = loadDSLFile(path)
            registry.addAll(loadedComponents)
        }
        return registry
    }

    fun loadDSLFile(dslFilePath: Path): Collection<Component> {
        val engine = try {
            KotlinJsr223DefaultScriptEngineFactory().scriptEngine
        } catch (e: Exception) {
            logger.info("Unable to get default kotlin script engine, fallback to local script engine")
            KotlinJsr223JvmLocalScriptEngineFactory().scriptEngine
        }
        currentRegistry.clear()
        Files.newBufferedReader(dslFilePath).use { reader ->
            logger.info("Loading $dslFilePath")
            engine.eval(reader)
        }
        return ArrayList(currentRegistry)
    }

    fun addRootComponent(component: Component) {
        currentRegistry.add(component)
    }

    fun getCurrentRegistry() = currentRegistry
}
