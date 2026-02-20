package org.octopusden.octopus.components.registry.dsl.script

import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.octopusden.octopus.components.registry.api.Component
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentMap
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
        // CRITICAL: Set all Kotlin compiler properties BEFORE setIdeaIoUseFallback()
        // setIdeaIoUseFallback() loads Kotlin compiler classes which immediately
        // read and cache these properties. If we set them after, it's too late!

        // 1. Normalize java.home on Windows (Kotlin compiler constructs URIs from it)
        if (isWindows) {
            val javaHome = System.getProperty("java.home")
            logger.info("Windows detected. Current java.home = $javaHome")
            if (javaHome != null && javaHome.contains('\\')) {
                val normalizedJavaHome = javaHome.replace('\\', '/')
                logger.info("Normalizing java.home to: $normalizedJavaHome")
                System.setProperty("java.home", normalizedJavaHome)
            }
        }

        // 2. Set kotlin.script.classpath (or normalize if already set by StartupApplicationListener)
        val existingClasspath = System.getProperty("kotlin.script.classpath")
        if (existingClasspath.isNullOrEmpty()) {
            val custom = System.getProperty("cr.dsl.class.path") ?: resolveClasspath()
            logger.info("Setting kotlin.script.classpath with ${custom.split(File.pathSeparator).size} entries")
            System.setProperty("kotlin.script.classpath", custom)
        } else if (isWindows && existingClasspath.contains('\\')) {
            // StartupApplicationListener may have set classpath with backslash paths
            val normalized = existingClasspath.split(File.pathSeparator)
                .joinToString(File.pathSeparator) { it.replace('\\', '/') }
            logger.info("Normalizing existing kotlin.script.classpath (${normalized.split(File.pathSeparator).size} entries)")
            System.setProperty("kotlin.script.classpath", normalized)
        }

        // 3. Normalize kotlin.java.stdlib.jar if set with backslashes
        if (isWindows) {
            val stdlibJar = System.getProperty("kotlin.java.stdlib.jar")
            if (stdlibJar != null && stdlibJar.contains('\\')) {
                System.setProperty("kotlin.java.stdlib.jar", stdlibJar.replace('\\', '/'))
            }
        }

        // Log first 3 classpath entries to verify path format
        System.getProperty("kotlin.script.classpath")?.split(File.pathSeparator)?.take(3)?.forEachIndexed { i, path ->
            logger.info("  kotlin.script.classpath entry $i: $path")
        }

        // 4. On Windows, pre-populate the Kotlin compiler's JRT filesystem cache BEFORE
        // setIdeaIoUseFallback(). This must happen first because setIdeaIoUseFallback() loads
        // Kotlin compiler classes which may trigger CoreJrtFileSystem initialization.
        // Without this, CoreJrtFileSystem creates File(java.home).absolutePath which converts
        // forward slashes back to backslashes, then passes that to
        // FileSystems.newFileSystem("jrt:/", ...) which fails with IOException on Windows.
        if (isWindows) {
            prePopulateJrtFsCache(this::class.java.classLoader)
        }

        // 5. NOW it's safe to initialize Kotlin compiler
        setIdeaIoUseFallback()
    }

    /**
     * Pre-populate CoreJrtFileSystem.globalJrtFsCache with the default JRT filesystem.
     * Tries multiple classloaders to handle classloader isolation in Spring Boot fat JAR.
     *
     * SOLUTIONS IMPLEMENTED:
     * #1: Fallback if default JRT filesystem is not available
     * #2: Try multiple classloaders to handle isolation
     * #3: Multiple verification to handle ConcurrentFactoryMap behavior
     */
    private fun prePopulateJrtFsCache(classLoader: ClassLoader) {
        val javaHomeProperty = System.getProperty("java.home")
        val jdkHomeKey = File(javaHomeProperty).absolutePath

        logger.info("[JRT-CACHE] Attempting to pre-populate JRT cache (key: $jdkHomeKey)")

        // SOLUTION #1: Handle silent failures - get default JRT filesystem with fallback
        val defaultJrtFs = try {
            FileSystems.getFileSystem(URI.create("jrt:/"))
        } catch (e: Exception) {
            logger.warning("[JRT-CACHE] Default JRT filesystem not found, attempting to create with empty config")
            try {
                // Try creating JRT filesystem without java.home parameter to avoid Windows path issues
                FileSystems.newFileSystem(URI.create("jrt:/"), emptyMap<String, Any>())
            } catch (e2: Exception) {
                logger.severe("[JRT-CACHE] CRITICAL: Cannot get or create JRT filesystem. " +
                    "Relying on in-process compiler execution to avoid this issue.")
                // Don't throw - let in-process compiler execution handle it
                return
            }
        }
        logger.info("[JRT-CACHE] Obtained JRT filesystem: $defaultJrtFs")

        // SOLUTION #2: Try multiple classloaders to handle classloader isolation
        val classLoadersToTry = listOf(
            "Primary" to classLoader,
            "Current" to this::class.java.classLoader,
            "System" to ClassLoader.getSystemClassLoader(),
            "Thread Context" to Thread.currentThread().contextClassLoader
        ).distinctBy { it.second }  // Remove duplicates

        logger.info("[JRT-CACHE] Trying ${classLoadersToTry.size} distinct classloader(s)")

        var successCount = 0
        for ((name, loader) in classLoadersToTry) {
            try {
                val coreJrtClass = Class.forName(
                    "org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem", true, loader
                )
                val cacheField = coreJrtClass.getDeclaredField("globalJrtFsCache")
                cacheField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val cache = cacheField.get(null) as ConcurrentMap<String, FileSystem>

                // SOLUTION #3: Handle ConcurrentFactoryMap - use put() and verify multiple times
                cache.put(jdkHomeKey, defaultJrtFs)

                // Immediate verification
                val verified1 = cache[jdkHomeKey]
                // Second verification with get() method
                val verified2 = cache.get(jdkHomeKey)

                if (verified1 === defaultJrtFs && verified2 === defaultJrtFs) {
                    successCount++
                    logger.info("[JRT-CACHE] ✓ Success via $name classloader " +
                        "(cache@${System.identityHashCode(cache).toString(16)})")
                } else {
                    logger.warning("[JRT-CACHE] ✗ Verification failed via $name classloader " +
                        "(v1=$verified1, v2=$verified2)")
                }
            } catch (e: ClassNotFoundException) {
                logger.fine("[JRT-CACHE] CoreJrtFileSystem not found via $name classloader")
            } catch (e: Exception) {
                logger.warning("[JRT-CACHE] Failed via $name classloader: ${e.message}")
            }
        }

        if (successCount > 0) {
            logger.info("[JRT-CACHE] ✓ Pre-populated via $successCount classloader(s)")
        } else {
            logger.warning("[JRT-CACHE] ✗ Failed for all classloaders. " +
                "Relying on in-process compiler execution strategy.")
        }
    }

    private fun resolveClasspath(): String {
        val javaClassPath = System.getProperty("java.class.path") ?: ""
        val extracted = extractedBootInfJarPaths
        if (extracted.isEmpty()) return javaClassPath

        // On Windows, normalize paths to use forward slashes for the Kotlin script engine
        // The Kotlin compiler expects forward slashes in classpath entries, even on Windows
        val normalizedPaths = if (isWindows) {
            extracted.map { it.replace('\\', '/') }
        } else {
            extracted
        }

        return normalizedPaths.joinToString(File.pathSeparator)
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
                // Use short temp dir name to avoid Windows MAX_PATH (260 char) limit
                val tempDir = Files.createTempDirectory("ks-")
                logger.info("Windows: created temp directory at ${tempDir.toAbsolutePath()}")
                Runtime.getRuntime().addShutdownHook(Thread { tempDir.toFile().deleteRecursively() })

                for (entry in bootInfEntries) {
                    val jarName = entry.name.substringAfterLast('/')
                    val targetFile = tempDir.resolve(jarName).toFile()
                    if (!targetFile.exists()) {
                        jar.getInputStream(entry).use { input ->
                            targetFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    // Use canonical path to resolve any symlinks or path issues
                    val canonicalPath = try {
                        targetFile.canonicalPath
                    } catch (e: Exception) {
                        logger.warning("Failed to get canonical path for ${targetFile.absolutePath}, using absolute path: ${e.message}")
                        targetFile.absolutePath
                    }
                    paths.add(canonicalPath)
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
            // Re-populate JRT cache using the context classloader that the REPL compiler will use.
            // This handles classloader isolation in Spring Boot fat JAR where the compiler may
            // load CoreJrtFileSystem through a different classloader than the init block used.
            if (isWindows) {
                prePopulateJrtFsCache(Thread.currentThread().contextClassLoader)
            }
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
