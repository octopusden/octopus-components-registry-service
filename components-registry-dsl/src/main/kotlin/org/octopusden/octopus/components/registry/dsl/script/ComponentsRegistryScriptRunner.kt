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
        val engine = KotlinJsr223DefaultScriptEngineFactory().scriptEngine
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

    fun decodeParameters(name: String): ProductTypes =
        productTypeMap[name] ?: throw IllegalArgumentException("Unknown product type $name")

    fun encodeParameters(type: ProductTypes): String =
        productTypeMap.filter { it.value == type }.keys.firstOrNull {
            it == null
        } ?: throw IllegalArgumentException("Unknown product type $type")

    fun getCurrentRegistry() = currentRegistry
    fun getProductTypeMap()  = productTypeMap
}
