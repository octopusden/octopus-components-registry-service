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
        val existing = System.getProperty("kotlin.script.classpath")
        logger.info("kotlin.script.classpath at init = $existing")
        if (existing.isNullOrEmpty()) {
            val custom = System.getProperty("cr.dsl.class.path", System.getProperty("java.class.path"))
            System.setProperty("kotlin.script.classpath", custom)
            logger.info("Setting kotlin.script.classpath manually = $custom")
        } else {
            logger.info("Using existing kotlin.script.classpath = $existing")
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
            val currentCl = Thread.currentThread().contextClassLoader
            logger.info("Current context classloader = $currentCl")

            val resource = currentCl.getResource("org/jetbrains/kotlin/mainKts/MainKtsScript.class")
            logger.info("MainKtsScript resource = $resource")

            if (resource == null) {
                val properCl = this::class.java.classLoader
                logger.info("Switching context classloader to $properCl")
                Thread.currentThread().contextClassLoader = properCl
            }

            logger.info("Trying KotlinJsr223DefaultScriptEngineFactory...")
            KotlinJsr223DefaultScriptEngineFactory().scriptEngine.also {
                logger.info("Using KotlinJsr223DefaultScriptEngineFactory")
            }
        } catch (e: Throwable) {
            logger.warning("Default engine failed: ${e::class.java.simpleName}: ${e.message}, switching to LocalKotlinEngineFactory")
            e.printStackTrace()
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
            logger.severe("DSL evaluation failed for $dslFilePath: ${e::class.java.simpleName}: ${evalEx.message}")
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
