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
import java.util.concurrent.ConcurrentHashMap
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
        logger.info("=== ComponentsRegistryScriptRunner Initialization ===")
        logger.info("[DIAGNOSTIC] OS: ${System.getProperty("os.name")}, isWindows=$isWindows")
        logger.info("[DIAGNOSTIC] JAVA_HOME env var: ${System.getenv("JAVA_HOME")}")
        logger.info("[DIAGNOSTIC] java.home property: ${System.getProperty("java.home")}")

        // CRITICAL: Set all Kotlin compiler properties BEFORE setIdeaIoUseFallback()
        // setIdeaIoUseFallback() loads Kotlin compiler classes which immediately
        // read and cache these properties. If we set them after, it's too late!

        // 1. Normalize java.home on Windows (Kotlin compiler constructs URIs from it)
        if (isWindows) {
            val javaHome = System.getProperty("java.home")
            logger.info("[DIAGNOSTIC] Windows detected. Current java.home = $javaHome (length: ${javaHome?.length})")
            if (javaHome != null && javaHome.contains('\\')) {
                val normalizedJavaHome = javaHome.replace('\\', '/')
                logger.info("[DIAGNOSTIC] Normalizing java.home to: $normalizedJavaHome")
                System.setProperty("java.home", normalizedJavaHome)
            }
        }

        // Check if in-process compiler execution is enabled (PRIMARY FIX for Windows)
        val compilerStrategy = System.getProperty("kotlin.compiler.execution.strategy")
        logger.info("[DIAGNOSTIC] kotlin.compiler.execution.strategy = $compilerStrategy " +
            "(expected: 'in-process' for Windows compatibility)")
        if (isWindows && compilerStrategy != "in-process") {
            logger.warning("[DIAGNOSTIC] ⚠️ WARNING: In-process compiler execution NOT enabled on Windows! " +
                "This may cause subprocess spawning issues. Add: -Dkotlin.compiler.execution.strategy=in-process")
        }

        // ADDITIONAL WINDOWS FIXES: Set compiler temp/cache directories to short paths
        if (isWindows) {
            try {
                // Create a very short temp directory for Kotlin compiler to use
                val kotlinTempDir = Files.createTempDirectory("kt-")
                val kotlinTempPath = kotlinTempDir.toAbsolutePath().toString()
                logger.info("[DIAGNOSTIC] Created Kotlin compiler temp dir: $kotlinTempPath (length: ${kotlinTempPath.length})")

                // Override java.io.tmpdir for the Kotlin compiler to use our short temp dir
                // This prevents the compiler from creating deeply nested paths that exceed MAX_PATH
                val originalTmpDir = System.getProperty("java.io.tmpdir")
                System.setProperty("java.io.tmpdir", kotlinTempPath)
                logger.info("[DIAGNOSTIC] Overriding java.io.tmpdir: $originalTmpDir → $kotlinTempPath")

                // Clean up on shutdown
                Runtime.getRuntime().addShutdownHook(Thread {
                    try {
                        System.setProperty("java.io.tmpdir", originalTmpDir)
                        kotlinTempDir.toFile().deleteRecursively()
                    } catch (e: Exception) {
                        // Ignore cleanup errors
                    }
                })

                // Set Kotlin script compilation cache to a short path
                System.setProperty("kotlin.script.compilation.cache.dir", kotlinTempPath)
                logger.info("[DIAGNOSTIC] Set kotlin.script.compilation.cache.dir = $kotlinTempPath")

            } catch (e: Exception) {
                logger.warning("[DIAGNOSTIC] Failed to create Kotlin temp directory: ${e.message}")
            }
        }

        // 2. Set kotlin.script.classpath (or normalize if already set by StartupApplicationListener)
        val existingClasspath = System.getProperty("kotlin.script.classpath")
        if (existingClasspath.isNullOrEmpty()) {
            val custom = System.getProperty("cr.dsl.class.path") ?: resolveClasspath()
            logger.info("[DIAGNOSTIC] Setting kotlin.script.classpath with ${custom.split(File.pathSeparator).size} entries")
            System.setProperty("kotlin.script.classpath", custom)
        } else if (isWindows && existingClasspath.contains('\\')) {
            // StartupApplicationListener may have set classpath with backslash paths
            val normalized = existingClasspath.split(File.pathSeparator)
                .joinToString(File.pathSeparator) { it.replace('\\', '/') }
            logger.info("[DIAGNOSTIC] Normalizing existing kotlin.script.classpath (${normalized.split(File.pathSeparator).size} entries)")
            System.setProperty("kotlin.script.classpath", normalized)
        } else {
            logger.info("[DIAGNOSTIC] kotlin.script.classpath already set with ${existingClasspath.split(File.pathSeparator).size} entries")
        }

        // 3. On Windows, kotlin.java.stdlib.jar should NOT be set (causes path issues)
        // The compiler will find stdlib from kotlin.script.classpath
        val stdlibJar = System.getProperty("kotlin.java.stdlib.jar")
        logger.info("[DIAGNOSTIC] kotlin.java.stdlib.jar = $stdlibJar")
        if (isWindows && stdlibJar != null) {
            logger.warning("[DIAGNOSTIC] ⚠️ WARNING: kotlin.java.stdlib.jar is set on Windows!")
            logger.warning("[DIAGNOSTIC] This may cause 'filename syntax incorrect' errors.")
            logger.warning("[DIAGNOSTIC] Consider unsetting this property on Windows.")
        }

        // Log first 3 classpath entries to verify path format and check path lengths
        System.getProperty("kotlin.script.classpath")?.split(File.pathSeparator)?.take(3)?.forEachIndexed { i, path ->
            logger.info("[DIAGNOSTIC] kotlin.script.classpath[$i]: $path (length: ${path.length})")
            if (isWindows && path.length > 200) {
                logger.warning("[DIAGNOSTIC] ⚠️ Path length ${path.length} approaching MAX_PATH limit (260)")
            }
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

                // SOLUTION #3: Replace the entire cache with a custom map that ALWAYS returns
                // our pre-populated filesystem, bypassing any ConcurrentFactoryMap logic
                @Suppress("UNCHECKED_CAST")
                val customCache = object : ConcurrentMap<String, FileSystem> {
                    override val size: Int get() = 1
                    override val entries: MutableSet<MutableMap.MutableEntry<String, FileSystem>>
                        get() = mutableSetOf()
                    override val keys: MutableSet<String> get() = mutableSetOf()
                    override val values: MutableCollection<FileSystem> get() = mutableListOf()

                    override fun get(key: String): FileSystem {
                        logger.fine("[JRT-CACHE] Custom cache get() called with key: $key")
                        return defaultJrtFs  // Always return our pre-populated filesystem
                    }

                    override fun containsKey(key: String): Boolean = true
                    override fun containsValue(value: FileSystem): Boolean = true
                    override fun isEmpty(): Boolean = false
                    override fun clear() {}
                    override fun put(key: String, value: FileSystem): FileSystem? = value
                    override fun putAll(from: Map<out String, FileSystem>) {}
                    override fun remove(key: String): FileSystem? = null
                    override fun remove(key: String, value: FileSystem): Boolean = false
                    override fun putIfAbsent(key: String, value: FileSystem): FileSystem? = value
                    override fun replace(key: String, oldValue: FileSystem, newValue: FileSystem): Boolean = false
                    override fun replace(key: String, value: FileSystem): FileSystem? = value
                } as ConcurrentHashMap<String, FileSystem>

                // Replace the cache field with our custom cache
                cacheField.set(null, customCache)

                // Verify replacement worked
                val replacedCache = cacheField.get(null)
                if (replacedCache === customCache) {
                    successCount++
                    logger.info("[JRT-CACHE] ✓ Replaced cache via $name classloader " +
                        "(cache@${System.identityHashCode(customCache).toString(16)})")
                } else {
                    logger.warning("[JRT-CACHE] ✗ Cache replacement failed via $name classloader")
                }
            } catch (e: ClassNotFoundException) {
                logger.fine("[JRT-CACHE] CoreJrtFileSystem not found via $name classloader")
            } catch (e: Exception) {
                logger.warning("[JRT-CACHE] Failed via $name classloader: ${e.message}")
            }
        }

        if (successCount > 0) {
            logger.info("[JRT-CACHE] ✓ Replaced globalJrtFsCache via $successCount classloader(s)")
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
                val tempDirPath = tempDir.toAbsolutePath().toString()
                logger.info("[DIAGNOSTIC] Created temp directory: $tempDirPath")
                logger.info("[DIAGNOSTIC] Temp dir path length: ${tempDirPath.length} (MAX_PATH limit: 260)")
                if (tempDirPath.length > 200) {
                    logger.warning("[DIAGNOSTIC] ⚠️ Temp directory path is long! May cause issues with nested files.")
                }
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
        logger.info("=== loadDSLFile: $dslFilePath ===")
        val absolutePath = dslFilePath.toAbsolutePath().toString()
        val normalizedPath = absolutePath.replace('\\', '/')
        logger.info("[DIAGNOSTIC] File absolute path: $absolutePath (length: ${absolutePath.length})")
        logger.info("[DIAGNOSTIC] File normalized path: $normalizedPath")
        logger.info("[DIAGNOSTIC] File URI: ${dslFilePath.toUri()}")
        logger.info("[DIAGNOSTIC] Current working directory: ${System.getProperty("user.dir")}")
        logger.info("[DIAGNOSTIC] Temp directory (java.io.tmpdir): ${System.getProperty("java.io.tmpdir")}")

        if (productTypeMap.isEmpty()) {
            products.forEach { k, v -> productTypeMap[v] = k }
        }

        val engine = try {
            logger.info("[DIAGNOSTIC] Setting context classloader to SafeContextClassLoader")
            val originalClassLoader = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = safeScriptClassLoader
            logger.info("[DIAGNOSTIC] Context classloader changed: ${originalClassLoader::class.java.simpleName} → ${safeScriptClassLoader::class.java.simpleName}")

            // Re-populate JRT cache using the context classloader that the REPL compiler will use.
            // This handles classloader isolation in Spring Boot fat JAR where the compiler may
            // load CoreJrtFileSystem through a different classloader than the init block used.
            if (isWindows) {
                logger.info("[DIAGNOSTIC] Re-populating JRT cache with context classloader before engine creation")
                prePopulateJrtFsCache(Thread.currentThread().contextClassLoader)
            }

            logger.info("[DIAGNOSTIC] Creating Kotlin script engine...")
            val startTime = System.currentTimeMillis()
            val scriptEngine = KotlinJsr223DefaultScriptEngineFactory().scriptEngine
            val duration = System.currentTimeMillis() - startTime
            logger.info("[DIAGNOSTIC] ✓ Script engine created successfully in ${duration}ms")
            logger.info("[DIAGNOSTIC] Engine: ${scriptEngine::class.java.name}")
            scriptEngine
        } catch (e: Throwable) {
            logger.warning("[DIAGNOSTIC] ✗ Failed to create default Kotlin script engine: ${e::class.java.simpleName}: ${e.message}")
            logger.warning("[DIAGNOSTIC] Stack trace:")
            e.printStackTrace()
            logger.info("[DIAGNOSTIC] Falling back to LocalKotlinEngineFactory")
            LocalKotlinEngineFactory().scriptEngine
        }

        currentRegistry.clear()
        try {
            // THEORY #1 FIX: Try using normalized file path for script context
            // Create a ScriptContext with normalized filename to avoid backslash issues
            val scriptContent = Files.readString(dslFilePath)
            val normalizedFileName = dslFilePath.toAbsolutePath().toString().replace('\\', '/')

            logger.info("[DIAGNOSTIC] Evaluating DSL script...")
            logger.info("[DIAGNOSTIC] Script content length: ${scriptContent.length} chars")
            logger.info("[DIAGNOSTIC] Using normalized filename: $normalizedFileName")

            val startTime = System.currentTimeMillis()

            // Try to set the filename in the engine context to use forward slashes
            try {
                engine.put(javax.script.ScriptEngine.FILENAME, normalizedFileName)
                logger.info("[DIAGNOSTIC] Set ScriptEngine.FILENAME to normalized path")
            } catch (e: Exception) {
                logger.warning("[DIAGNOSTIC] Could not set FILENAME in engine: ${e.message}")
            }

            // Evaluate the script content directly instead of using a Reader
            // This allows us to control the source filename
            engine.eval(scriptContent)

            val duration = System.currentTimeMillis() - startTime
            logger.info("[DIAGNOSTIC] ✓ DSL evaluation completed successfully in ${duration}ms")
            logger.info("Successfully loaded DSL from $dslFilePath")
        } catch (e: Throwable) {
            logger.severe("[DIAGNOSTIC] ✗ DSL evaluation FAILED for $dslFilePath")
            logger.severe("[DIAGNOSTIC] Exception type: ${e::class.java.name}")
            logger.severe("[DIAGNOSTIC] Exception message: ${e.message}")
            logger.severe("[DIAGNOSTIC] Stack trace:")
            e.printStackTrace()

            // Log additional diagnostic information
            logger.severe("[DIAGNOSTIC] ==== FULL EXCEPTION CHAIN ====")
            var cause: Throwable? = e
            var level = 0
            while (cause != null && level < 10) {
                logger.severe("[DIAGNOSTIC] Level $level: ${cause::class.java.name}")
                logger.severe("[DIAGNOSTIC]   Message: ${cause.message}")

                // Try to extract file/path information from the exception
                val msg = cause.message ?: ""
                if (msg.contains(":\\") || msg.contains(":/") || msg.contains("file") || msg.contains("path")) {
                    logger.severe("[DIAGNOSTIC]   *** This level mentions a file/path! ***")
                }

                cause = cause.cause
                level++
            }
            logger.severe("[DIAGNOSTIC] ==== END EXCEPTION CHAIN ====")

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
