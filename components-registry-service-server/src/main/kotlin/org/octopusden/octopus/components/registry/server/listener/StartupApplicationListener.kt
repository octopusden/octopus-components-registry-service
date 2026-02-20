package org.octopusden.octopus.components.registry.server.listener

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationStartingEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import java.io.File
import java.lang.IllegalStateException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import jakarta.annotation.PreDestroy
import kotlin.collections.ArrayList
import kotlin.streams.toList

/**
 * Sets System property 'kotlin.script.classpath' if application is started as boot jar.
 *
 * Note for run application as boot jar:
 * Kotlin scripting requires DSL libraries availability. Spring boot jar uses own classloader which doesn't connect with kotlin scripting and result in no class exception.
 * The DSL libraries have to be specified in system parameter 'kotlin.script.classpath' before scripting engine initializing.
 * The list of required libraries are stored in resources components-registry-dsl.txt file on components-registry-dsl module build phase.
 * The method extracts DSL libraries from boot jar archive in temporary directory and set system parameter 'kotlin.script.classpath' to that directory.
 * The library versions stored in components-registry-dsl.txt could be different than stored in boot jar.
 *
 */
@Component
class StartupApplicationListener: ApplicationListener<ApplicationStartingEvent> {
    companion object {
        private val LOG = LoggerFactory.getLogger(StartupApplicationListener::class.java)!!
        private val LIBRARY_VERSION_SPLIT_REGEXP = Regex("-[0-9]")
        private var temporaryLibraryPath: Path? = null
    }

    override fun onApplicationEvent(event: ApplicationStartingEvent) {
        LOG.info("=== StartupApplicationListener ===")
        val dslKotlinModule = Thread.currentThread().contextClassLoader.getResource("/components-registry-dsl.txt")
        LOG.info("[DIAGNOSTIC] dslKotlinModule resource: {}", dslKotlinModule)

        if (dslKotlinModule != null && dslKotlinModule.toString().contains("!BOOT-INF/")) {
            LOG.info("[DIAGNOSTIC] Spring Boot fat JAR mode detected")
            // Use short temp dir name to avoid Windows MAX_PATH (260 char) limit
            temporaryLibraryPath = Files.createTempDirectory("cr-dsl-")
            val tempPath = temporaryLibraryPath.toString()
            LOG.info("[DIAGNOSTIC] Created temp directory: {}", tempPath)
            LOG.info("[DIAGNOSTIC] Temp path length: {} (MAX_PATH limit: 260)", tempPath.length)
            if (tempPath.length > 200) {
                LOG.warn("[DIAGNOSTIC] ⚠️ Temp directory path is long! May cause issues.")
            }
            val dslLibraryClassPath = StringBuffer()
            FileSystems.newFileSystem(Paths.get(System.getProperty("java.class.path"))).use { fs ->
                val libraryFiles = ArrayList<Path>()
                Files.walk(fs.getPath("/BOOT-INF/lib")).use { filePath ->
                    libraryFiles.addAll(filePath.filter { it.toString().endsWith(".jar") }.toList())
                }

                // Fix for kotlin.java.stdlib.jar property issue in fat jar
                // CRITICAL: On Windows, do NOT set kotlin.java.stdlib.jar property!
                // Unit tests pass without it, but setting it causes "filename syntax incorrect" error
                // The Kotlin compiler can find stdlib from kotlin.script.classpath
                val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
                val stdlibJar = libraryFiles.find { it.fileName.toString().matches(Regex("kotlin-stdlib-\\d+.*\\.jar")) }

                if (stdlibJar != null && !isWindows) {
                    // Only set on non-Windows platforms
                    val dstPath = temporaryLibraryPath!!.resolve(stdlibJar.fileName.toString())
                    if (!Files.exists(dstPath)) {
                        Files.copy(stdlibJar, dstPath)
                    }
                    val normalizedPath = normalizePath(dstPath.toString())
                    System.setProperty("kotlin.java.stdlib.jar", normalizedPath)
                    LOG.info("[DIAGNOSTIC] Set kotlin.java.stdlib.jar = {}", normalizedPath)
                } else if (isWindows) {
                    LOG.info("[DIAGNOSTIC] Windows detected: Skipping kotlin.java.stdlib.jar property (not needed, causes path issues)")
                    LOG.info("[DIAGNOSTIC] Kotlin compiler will find stdlib from kotlin.script.classpath instead")
                } else {
                    LOG.error("[DIAGNOSTIC] ✗ Unable to find kotlin-stdlib jar in BOOT-INF/lib")
                }

                dslKotlinModule.openStream().use {inputStream ->
                    inputStream.reader().forEachLine { fileName ->
                        val libraryName = fileName.split(LIBRARY_VERSION_SPLIT_REGEXP, 2)[0]
                        val srcPath = libraryFiles.findLast { it.fileName.toString().split(LIBRARY_VERSION_SPLIT_REGEXP, 2)[0] == libraryName }
                                ?: throw IllegalStateException("Unable to match provided library $fileName")
                        val dstPath = temporaryLibraryPath!!.resolve(srcPath.fileName.toString())
                        if (!Files.exists(dstPath)) {
                            Files.copy(srcPath, dstPath)
                        }
                        dslLibraryClassPath.append(File.pathSeparator).append(normalizePath(dstPath.toString()))
                    }
                }
            }
            val classpath = dslLibraryClassPath.substring(1)
            System.setProperty("kotlin.script.classpath", classpath)
            val classpathEntries = classpath.split(File.pathSeparator)
            LOG.info("[DIAGNOSTIC] Set kotlin.script.classpath with {} entries", classpathEntries.size)

            // Log sample entries and check for path length issues
            classpathEntries.take(3).forEachIndexed { i, path ->
                LOG.info("[DIAGNOSTIC] Classpath[{}]: {} (length: {})", i, path, path.length)
                if (path.length > 200) {
                    LOG.warn("[DIAGNOSTIC] ⚠️ Classpath entry {} has length {} approaching MAX_PATH limit", i, path.length)
                }
            }

            LOG.info("[DIAGNOSTIC] ✓ Fat JAR library extraction complete")
        } else {
            val classpath = normalizePath(System.getProperty("cr.dsl.class.path", System.getProperty("java.class.path")))
            System.setProperty("kotlin.script.classpath", classpath)
            LOG.info("[DIAGNOSTIC] Not running as fat JAR, using standard classpath")
        }

        LOG.info("=== StartupApplicationListener Complete ===")
    }

    private fun normalizePath(path: String): String {
        return if (File.separatorChar == '\\') path.replace('\\', '/') else path
    }

    @PreDestroy
    fun deleteTemporary() {
        temporaryLibraryPath?.let {
            if (!it.toFile().deleteRecursively()) {
                LOG.warn("Fail to delete temporary directory $temporaryLibraryPath")
            }
        }
    }
}
