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

    /**
     * On Windows with a Spring Boot fat JAR, the default classloader (LaunchedURLClassLoader) exposes
     * jar:nested:/C:/... URLs that the Kotlin REPL can't convert to file paths.
     * This lazy classloader extracts those nested JARs to a temp dir and wraps them in a plain URLClassLoader.
     * On non-Windows, returns the original classloader unchanged.
     */
    private val safeScriptClassLoader: ClassLoader by lazy { buildSafeClassLoader() }

    init {
        setIdeaIoUseFallback()
        // Set kotlin.script.classpath if not already set
        if (System.getProperty("kotlin.script.classpath").isNullOrEmpty()) {
            val custom = System.getProperty("cr.dsl.class.path") ?: resolveClasspath()
            System.setProperty("kotlin.script.classpath", custom)
        }
    }

    /**
     * Resolves the classpath string for kotlin.script.classpath.
     * On non-Windows: returns java.class.path (existing behaviour).
     * On Windows: if jar:nested: URLs are detected (Spring Boot fat JAR), extracts all nested JARs
     * to a temp directory and returns real file paths so the Kotlin REPL can read them.
     */
    private fun resolveClasspath(): String {
        val javaClassPath = System.getProperty("java.class.path") ?: ""
        if (!isWindows) return javaClassPath

        val cl = this::class.java.classLoader
        val urls = getClassLoaderUrls(cl)
        if (urls.none { it.toString().startsWith("jar:nested:") }) return javaClassPath

        logger.info("Windows + Spring Boot fat JAR detected – extracting nested JARs for kotlin.script.classpath")
        val tempDir = Files.createTempDirectory("kotlin-script-cp")
        Runtime.getRuntime().addShutdownHook(Thread { tempDir.toFile().deleteRecursively() })

        val paths = mutableListOf<String>()
        for (url in urls) {
            when {
                url.protocol == "file" -> try { paths.add(File(url.toURI()).absolutePath) } catch (_: Exception) {}
                url.toString().startsWith("jar:nested:") ->
                    extractNestedJar(url.toString(), tempDir)?.let { paths.add(it) }
            }
        }
        return paths.joinToString(File.pathSeparator).ifEmpty { javaClassPath }
    }

    /**
     * Builds a URLClassLoader backed by real file: URLs on Windows fat JAR deployments.
     * On non-Windows (or when no jar:nested: URLs are present) returns the original classloader.
     */
    private fun buildSafeClassLoader(): ClassLoader {
        val cl = this::class.java.classLoader
        if (!isWindows) return cl

        val urls = getClassLoaderUrls(cl)
        if (urls.none { it.toString().startsWith("jar:nested:") }) return cl

        logger.info("Windows + Spring Boot fat JAR detected – building safe URLClassLoader for Kotlin scripting")
        val tempDir = Files.createTempDirectory("kotlin-script-cl")
        Runtime.getRuntime().addShutdownHook(Thread { tempDir.toFile().deleteRecursively() })

        val fileUrls = mutableListOf<URL>()
        for (url in urls) {
            when {
                url.protocol == "file" -> fileUrls.add(url)
                url.toString().startsWith("jar:nested:") ->
                    extractNestedJar(url.toString(), tempDir)?.let { fileUrls.add(File(it).toURI().toURL()) }
            }
        }
        return if (fileUrls.isNotEmpty()) URLClassLoader(fileUrls.toTypedArray(), cl.parent) else cl
    }

    /** Walks the classloader hierarchy and collects all URLs via reflection on getURLs(). */
    private fun getClassLoaderUrls(cl: ClassLoader): List<URL> {
        val urls = mutableListOf<URL>()
        var loader: ClassLoader? = cl
        while (loader != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                urls.addAll(loader.javaClass.getMethod("getURLs").invoke(loader) as Array<URL>)
            } catch (_: Exception) {}
            loader = loader.parent
        }
        return urls
    }

    /**
     * Extracts a single nested JAR entry to [tempDir] and returns its absolute path.
     *
     * URL format:  jar:nested:/path/to/app.jar/!BOOT-INF/lib/some.jar!/
     * Windows URL: jar:nested:/C:/path/to/app.jar/!BOOT-INF/lib/some.jar!/
     * The leading '/' before the drive letter is stripped to produce a valid Windows path.
     */
    private fun extractNestedJar(urlStr: String, tempDir: Path): String? {
        return try {
            val spec = urlStr.removePrefix("jar:nested:")
            val bangIdx = spec.indexOf("/!")
            if (bangIdx < 0) return null

            var outerPath = spec.substring(0, bangIdx)
            val innerPath = spec.substring(bangIdx + 2).trimEnd('/')

            // /C:/foo/bar.jar  →  C:/foo/bar.jar  (Windows drive-letter fix)
            if (outerPath.length >= 3 && outerPath[0] == '/' && outerPath[2] == ':') {
                outerPath = outerPath.substring(1)
            }

            val jarName = innerPath.substringAfterLast('/')
            val targetFile = tempDir.resolve(jarName).toFile()

            if (!targetFile.exists()) {
                JarFile(outerPath).use { jar ->
                    val entry = jar.getJarEntry(innerPath) ?: return null
                    jar.getInputStream(entry).use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            targetFile.absolutePath
        } catch (e: Exception) {
            logger.warning("Failed to extract nested JAR $urlStr: ${e.message}")
            null
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
            // Always switch to buildscript classloader where Kotlin scripting classes are loaded.
            // safeScriptClassLoader is a URLClassLoader with real file: URLs on Windows fat JARs,
            // avoiding the jar:nested: path conversion failures in the Kotlin REPL.
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
        productTypeMap.filter { it.value == type }.keys.firstOrNull {
            it == null
        } ?: throw IllegalArgumentException("Unknown product type $type")

    fun getCurrentRegistry() = currentRegistry
    fun getProductTypeMap() = productTypeMap
}
