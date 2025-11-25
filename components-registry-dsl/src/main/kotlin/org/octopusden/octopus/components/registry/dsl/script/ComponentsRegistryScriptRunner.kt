package org.octopusden.octopus.components.registry.dsl.script

import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.octopusden.octopus.components.registry.api.Component
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import java.util.stream.Collectors
import kotlin.script.experimental.jsr223.KotlinJsr223DefaultScriptEngineFactory

object ComponentsRegistryScriptRunner {
    private val logger = Logger.getLogger(ComponentsRegistryScriptRunner::class.java.canonicalName)
    private val currentRegistry = ArrayList<Component>()
    private val productTypeMap = HashMap<String, ProductTypes>()

    init {
        setIdeaIoUseFallback()
        // Set kotlin.script.classpath if not already set
        if (System.getProperty("kotlin.script.classpath").isNullOrEmpty()) {
            val custom = System.getProperty("cr.dsl.class.path", System.getProperty("java.class.path"))
            System.setProperty("kotlin.script.classpath", custom)
            logger.info("cr.dsl.class.path = ${System.getProperty("cr.dsl.class.path")}")
            logger.info("Setting kotlin.script.classpath= $custom")
        }
        
        // Set kotlin.java.stdlib.jar if not already set - required for Kotlin script engine initialization
        if (System.getProperty("kotlin.java.stdlib.jar").isNullOrEmpty()) {
            val classpath = System.getProperty("kotlin.script.classpath") ?: System.getProperty("java.class.path")
            val stdlibJar = classpath.split(System.getProperty("path.separator"))
                .firstOrNull { it.contains("kotlin-stdlib") && it.endsWith(".jar") }
            
            if (stdlibJar != null) {
                System.setProperty("kotlin.java.stdlib.jar", stdlibJar)
                logger.info("Setting kotlin.java.stdlib.jar=$stdlibJar")
            } else {
                logger.warning("Unable to find kotlin-stdlib jar in classpath")
            }
        }
    }

    /**
     * Load  kotlin DSL Components Registry.
     * For boot jar based application System property 'kotlin.script.classpath' has to be set to DSL libraries before call.
     */
    fun loadDSL(basePath: Path, products: Map<ProductTypes, String>): Collection<Component> {
        logger.info("loadDSL from $basePath")
        val registry = ArrayList<Component>()
        val ktsFiles = Files.list(basePath).filter { path -> path.fileName.toString().endsWith(".kts") }.toList()
        logger.info("File list = ${ktsFiles.stream().map { it.fileName.toString() }.collect(Collectors.joining(","))}")
        ktsFiles.forEach { path ->
            val loadedComponents = loadDSLFile(path, products)
            registry.addAll(loadedComponents)
        }
        return registry
    }

    fun loadDSLFile(dslFilePath: Path, products: Map<ProductTypes, String>): Collection<Component> {
        logger.info("loadDSLFile $dslFilePath")

        if (productTypeMap.isEmpty()) {
            products.forEach { k, v -> productTypeMap[v] = k }
        }

        val engine = try {
            // Always switch to buildscript classloader where Kotlin scripting classes are loaded
            val properCl = this::class.java.classLoader
            Thread.currentThread().contextClassLoader = properCl

            KotlinJsr223DefaultScriptEngineFactory().scriptEngine.also {
                logger.info("Using KotlinJsr223DefaultScriptEngineFactory")
            }
        } catch (_: Throwable) {
            logger.info("Unable to get default kotlin script engine, fallback to local script engine")
            LocalKotlinEngineFactory().scriptEngine
        }
        currentRegistry.clear()
        try {
            Files.newBufferedReader(dslFilePath).use { reader ->
                logger.info("Loading $dslFilePath")
                engine.eval(reader)
            }
            logger.info("Successfully loaded DSL from $dslFilePath")
        } catch (e: Throwable) {
            logger.severe("DSL evaluation failed for $dslFilePath: ${e::class.java.simpleName}: ${e.message}")
            e.printStackTrace()
            throw e
        }
        return ArrayList(currentRegistry)
    }

    fun addRootComponent(component: Component) {
        currentRegistry.add(component)
    }

    fun decodeParameters(name: String): ProductTypes =
        productTypeMap[name] ?: throw IllegalArgumentException("Unknown product type $name")

    fun encodeParameters(type: ProductTypes): String =
        productTypeMap.filter { it.value == type }.keys.firstOrNull {
            it == null
        } ?: throw IllegalArgumentException("Unknown product type $type")

    fun getCurrentRegistry() = currentRegistry
    fun getProductTypeMap() = productTypeMap
}
