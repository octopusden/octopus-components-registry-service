package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Integration test that verifies the fat jar can start successfully.
 * This test specifically checks for the Kotlin stdlib issue that occurs
 * when running as a Spring Boot fat jar in production environments.
 *
 * This test is designed to detect folloqing errors:
 * - "Unable to find kotlin stdlib" error
 * - "Cannot access script base class" error
 *
 * Also:
 * - Dynamic port allocation
 * - HTTP Health check verification
 */
class FatJarStartupIntegrationTest {

    @Test
    @Timeout(120) // 2 minutes timeout
    fun `fat jar should start successfully and load Kotlin DSL without errors`(@TempDir tempDir: Path) {
        val fatJarPath = System.getProperty("fatJar.path")
            ?: throw IllegalStateException("fatJar.path system property not set")
        
        val fatJar = File(fatJarPath)
        Assertions.assertTrue(fatJar.exists(), "Fat jar not found at: $fatJarPath")
        
        println("=== Fat Jar Startup Integration Test ===")
        println("Fat jar: ${fatJar.absolutePath}")
        
        // Setup DSL files
        val dslDir = setupDslFiles(tempDir)
        val projectRegistryFile = tempDir.resolve("project-registry.json")
        Files.writeString(projectRegistryFile, "{}")
        
        // Find free port
        val port = findRandomPort()
        println("Using random port: $port")
        
        // Build command
        val javaHome = System.getProperty("java.home")
        val javaExecutable = File(javaHome, "bin/java")
        
        val command = listOf(
            javaExecutable.absolutePath,
            "-Dspring.cloud.config.enabled=false",
            "-Dspring.profiles.active=default",
            "-Dcomponents-registry.groovy-path=${dslDir.toAbsolutePath()}",
            "-Dcomponents-registry.work-dir=${tempDir.toAbsolutePath()}",
            "-Dcomponents-registry.main-groovy-file=Aggregator.groovy",
            "-Dcomponents-registry.vcs.enabled=false",
            "-Dcomponents-registry.version-name.service-branch=serviceBranch",
            "-Dcomponents-registry.version-name.service=service",
            "-Dcomponents-registry.version-name.minor=minor",
            "-Dcomponents-registry.product-type.c=PT_C",
            "-Dcomponents-registry.product-type.k=PT_K",
            "-Dcomponents-registry.product-type.d=PT_D",
            "-Dcomponents-registry.product-type.ddb=PT_D_DB",
            "-Dcomponents-registry.project-registry-path=${projectRegistryFile.toAbsolutePath()}",
            "-Dserver.port=$port",
            "-Dmanagement.endpoints.web.exposure.include=health", // Ensure health endpoint is exposed
            "-jar",
            fatJar.absolutePath
        )
        
        println("Starting application...")
        val processBuilder = ProcessBuilder(command)
        processBuilder.redirectErrorStream(true)
        
        val process = processBuilder.start()
        val output = StringBuilder()
        
        // Consume output in separate thread to prevent blocking and capture logs
        val loggerThread = thread(start = true) {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    synchronized(output) {
                        output.appendLine(line)
                    }
                    println(line) // Echo to stdout for CI visibility
                }
            }
        }
        
        try {
            // Wait for health check
            val healthy = waitForHealth(port, 90) // 90 seconds timeout
            
            if (!healthy) {
                // If not healthy, check if process is even alive
                if (!process.isAlive) {
                    val exitCode = process.exitValue()
                    Assertions.fail<String>("Application exited prematurely with code $exitCode. Check logs above.")
                }
                Assertions.fail<String>("Application did not become healthy within timeout.")
            }
            
            println("\n✅ Application is healthy!")
            
            // Verify no specific errors in logs (even if healthy, we don't want these errors)
            val logContent = synchronized(output) { output.toString() }
            
            Assertions.assertFalse(
                logContent.contains("kotlin stdlib", ignoreCase = true) && 
                logContent.contains("please specify it explicitly", ignoreCase = true),
                "Found 'kotlin stdlib' error in logs despite startup"
            )
            
            Assertions.assertFalse(
                logContent.contains("Cannot access script base class", ignoreCase = true),
                "Found 'script base class' error in logs despite startup"
            )
            
        } finally {
            // Save full output to file for debugging
            val logFile = File(System.getProperty("java.io.tmpdir"), "fat-jar-integration-test-${System.currentTimeMillis()}.log")
            val logContent = synchronized(output) { output.toString() }
            logFile.writeText(logContent)
            println("Full log saved to: ${logFile.absolutePath}")

            println("Shutting down application...")
            process.destroy()
            process.waitFor(10, TimeUnit.SECONDS)
            if (process.isAlive) {
                process.destroyForcibly()
            }
            loggerThread.join(2000)
        }
    }
    
    private fun setupDslFiles(tempDir: Path): Path {
        val testResourcesPath = System.getProperty("test.resources.path")
            ?: throw IllegalStateException("test.resources.path system property not set")
        
        val resourcesDir = File(testResourcesPath)
        Assertions.assertTrue(resourcesDir.exists(), "Test resources directory not found")
        
        val dslDir = tempDir.resolve("dsl")
        Files.createDirectories(dslDir)
        
        File(resourcesDir, "Aggregator.groovy").copyTo(dslDir.resolve("Aggregator.groovy").toFile())
        File(resourcesDir, "TestComponent.groovy").copyTo(dslDir.resolve("TestComponent.groovy").toFile())
        File(resourcesDir, "test.kts").copyTo(dslDir.resolve("test.kts").toFile())
        
        return dslDir
    }
    
    private fun findRandomPort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }
    
    private fun waitForHealth(port: Int, timeoutSeconds: Int): Boolean {
        val endTime = System.currentTimeMillis() + (timeoutSeconds * 1000)
        val healthUrl = URL("http://localhost:$port/actuator/health")
        
        println("Waiting for health check at $healthUrl...")
        
        while (System.currentTimeMillis() < endTime) {
            try {
                with(healthUrl.openConnection() as HttpURLConnection) {
                    requestMethod = "GET"
                    connectTimeout = 1000
                    readTimeout = 1000
                    connect()
                    
                    if (responseCode == 200) {
                        return true
                    }
                }
            } catch (e: Exception) {
                // Ignore connection failures while starting
                Thread.sleep(1000)
            }
        }
        return false
    }
}
