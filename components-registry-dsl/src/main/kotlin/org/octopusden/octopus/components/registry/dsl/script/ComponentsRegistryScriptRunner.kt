package org.octopusden.octopus.components.registry.dsl.script

import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.octopusden.octopus.components.registry.api.Component
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.logging.Logger
import java.util.stream.Collectors
import kotlin.script.experimental.jsr223.KotlinJsr223DefaultScriptEngineFactory

object ComponentsRegistryScriptRunner {
    private val logger = Logger.getLogger(ComponentsRegistryScriptRunner::class.java.canonicalName)
    private val currentRegistry = ArrayList<Component>()
    private val productTypeMap = HashMap<String, ProductTypes>()

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    // On Windows + Spring Boot fat JAR: extracts all BOOT-INF/lib/*.jar to a temp dir once.
    // Used for both kotlin.script.classpath and SafeContextClassLoader.
    private val extractedBootInfJarPaths: List<String> by lazy { extractBootInfLibJars() }

    // Wraps the Spring Boot classloader: exposes file: URLs to hide jar:nested:/C:/... from the
    // Kotlin REPL, but delegates all loadClass() calls to the original loader for class identity.
    private class SafeContextClassLoader(
        fileUrls: Array<URL>,
        private val delegate: ClassLoader
    ) : URLClassLoader(fileUrls, null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = delegate.loadClass(name)
        override fun getResource(name: String): URL? = delegate.getResource(name)
        override fun getResources(name: String): java.util.Enumeration<URL> = delegate.getResources(name)
        override fun getResourceAsStream(name: String): java.io.InputStream? = delegate.getResourceAsStream(name)
    }

    private val safeScriptClassLoader: ClassLoader by lazy { buildSafeClassLoader() }

    init {
        setIdeaIoUseFallback()
        if (System.getProperty("kotlin.script.classpath").isNullOrEmpty()) {
            val custom = System.getProperty("cr.dsl.class.path") ?: resolveClasspath()
            System.setProperty("kotlin.script.classpath", custom)
        }
    }

    private fun resolveClasspath(): String {
        val javaClassPath = System.getProperty("java.class.path") ?: ""
        val extracted = extractedBootInfJarPaths
        return if (extracted.isNotEmpty()) extracted.joinToString(File.pathSeparator) else javaClassPath
    }

    private fun buildSafeClassLoader(): ClassLoader {
        val cl = this::class.java.classLoader
        val extracted = extractedBootInfJarPaths
        if (extracted.isEmpty()) return cl
        logger.info("Building SafeContextClassLoader with ${extracted.size} extracted JARs")
        val fileUrls = extracted.map { File(it).toURI().toURL() }.toTypedArray()
        return SafeContextClassLoader(fileUrls, cl)
    }

    private fun extractBootInfLibJars(): List<String> {
        if (!isWindows) return emptyList()

        val javaClassPath = System.getProperty("java.class.path") ?: return emptyList()
        if (javaClassPath.contains(File.pathSeparator)) return emptyList()

        val fatJarFile = File(javaClassPath)
        if (!fatJarFile.exists() || !fatJarFile.name.endsWith(".jar")) return emptyList()

        val paths = mutableListOf<String>()
        try {
            JarFile(fatJarFile).use { jar ->
                val bootInfEntries = jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("BOOT-INF/lib/") && it.name.endsWith(".jar") }
                    .toList()

                if (bootInfEntries.isEmpty()) return emptyList()

                logger.info("Windows: extracting ${bootInfEntries.size} nested JARs from fat JAR for Kotlin scripting")
                val tempDir = Files.createTempDirectory("kotlin-script")
                Runtime.getRuntime().addShutdownHook(Thread { tempDir.toFile().deleteRecursively() })

                for (entry in bootInfEntries) {
                    val jarName = entry.name.substringAfterLast('/')
                    val targetFile = tempDir.resolve(jarName).toFile()
                    if (!targetFile.exists()) {
                        jar.getInputStream(entry).use { input ->
                            targetFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    paths.add(targetFile.absolutePath)
                }
            }
        } catch (e: Exception) {
            logger.warning("Windows: failed to extract fat JAR contents: ${e.message}")
            return emptyList()
        }

        logger.info("Windows: extracted ${paths.size} JARs for Kotlin scripting classpath")
        return paths
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
            Thread.currentThread().contextClassLoader = safeScriptClassLoader
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
        productTypeMap.entries.firstOrNull { it.value == type }?.key
            ?: throw IllegalArgumentException("Unknown product type $type")

    fun getCurrentRegistry() = currentRegistry
    fun getProductTypeMap() = productTypeMap
}
