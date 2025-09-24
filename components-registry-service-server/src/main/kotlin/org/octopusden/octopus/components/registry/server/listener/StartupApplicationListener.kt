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
        val dslKotlinModule = Thread.currentThread().contextClassLoader.getResource("/components-registry-dsl.txt")
        if (dslKotlinModule != null && dslKotlinModule.toString().matches(Regex(".*!/BOOT-INF/.*"))) {
            LOG.debug("Spring boot jar running mode detected")
            temporaryLibraryPath = Files.createTempDirectory("components-registry-dsl-" + UUID.randomUUID())
            val dslLibraryClassPath = StringBuffer()
            FileSystems.newFileSystem(Paths.get(System.getProperty("java.class.path"))).use { fs ->
                val libraryFiles = ArrayList<Path>()
                Files.walk(fs.getPath("/BOOT-INF/lib")).use { filePath ->
                    libraryFiles.addAll(filePath.filter { it.toString().endsWith(".jar") }.toList())
                }
                dslKotlinModule.openStream().use {inputStream ->
                    inputStream.reader().forEachLine { fileName ->
                        val libraryName = fileName.split(LIBRARY_VERSION_SPLIT_REGEXP, 2)[0]
                        val srcPath = libraryFiles.findLast { it.fileName.toString().split(LIBRARY_VERSION_SPLIT_REGEXP, 2)[0] == libraryName }
                                ?: throw IllegalStateException("Unable to match provided library $fileName")
                        val dstPath = temporaryLibraryPath!!.resolve(srcPath.fileName.toString())
                        Files.copy(srcPath, dstPath)
                        dslLibraryClassPath.append(File.pathSeparator).append(dstPath)
                    }
                }
            }
            System.setProperty("kotlin.script.classpath", dslLibraryClassPath.substring(1))
        } else {
            System.setProperty("kotlin.script.classpath", System.getProperty("cr.dsl.class.path", System.getProperty("java.class.path")))
        }
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
