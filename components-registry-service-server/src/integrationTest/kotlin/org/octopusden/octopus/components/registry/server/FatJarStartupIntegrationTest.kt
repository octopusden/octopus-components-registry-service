package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
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
 * This test is designed to detect following errors:
 * - "Unable to find kotlin stdlib" error
 * - "Cannot access script base class" error
 *
 * Also:
 * - Dynamic port allocation
 * - HTTP Health check verification
 */
class FatJarStartupIntegrationTest {
    
    private val log = LoggerFactory.getLogger(FatJarStartupIntegrationTest::class.java)

    @Test
    @Timeout(120) // 2 minutes timeout
    fun `fat jar should start successfully and load Kotlin DSL without errors`(@TempDir tempDir: Path) {
        val fatJarPath = System.getProperty("fatJar.path")
            ?: throw IllegalStateException("fatJar.path system property not set")
        
        val fatJar = File(fatJarPath)
        Assertions.assertTrue(fatJar.exists(), "Fat jar not found at: $fatJarPath")
        
        log.info("Fat jar: {}", fatJar.absolutePath)
        
        // Setup DSL files
        val dslDir = setupDslFiles(tempDir)
        val projectRegistryFile = tempDir.resolve("project-registry.json")
        Files.writeString(projectRegistryFile, "{}")
        
        // Find free port
        val port = findRandomPort()
        log.info("Using random port: {}", port)
        
        // Build command
        val javaHome = System.getProperty("java.home")
        val javaExecutable = File(javaHome, "bin/java")
        
        // Get path to integration test profile
        val testResourcesPath = System.getProperty("test.resources.path")
            ?: throw IllegalStateException("test.resources.path system property not set")
        val profilePath = File(testResourcesPath, "application-integration-test.yml")
        Assertions.assertTrue(profilePath.exists(), "Integration test profile not found at: ${profilePath.absolutePath}")
        
        val command = listOf(
            javaExecutable.absolutePath,
            "-Dspring.profiles.active=integration-test",
            "-Dspring.config.additional-location=file:${profilePath.absolutePath}",
            "-Dcomponents-registry.groovy-path=${dslDir.toAbsolutePath()}",
            "-Dcomponents-registry.work-dir=${tempDir.toAbsolutePath()}",
            "-Dcomponents-registry.project-registry-path=${projectRegistryFile.toAbsolutePath()}",
            "-Dserver.port=$port",
            "-jar",
            fatJar.absolutePath
        )
        
        log.info("Starting application...")
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
                    log.info("App output: {}", line)
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
            
            log.info("Application is healthy")
            
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
            log.info("Full log saved to: {}", logFile.absolutePath)

            log.info("Shutting down application...")
            process.destroy()
            process.waitFor(10, TimeUnit.SECONDS)
            if (process.isAlive) {
                process.destroyForcibly()
            }
            loggerThread.join(2000)
        }
    }
    
    private fun setupDslFiles(tempDir: Path): Path {
        val dslDir = tempDir.resolve("dsl")
        Files.createDirectories(dslDir)
        
        val testResourcesPath = System.getProperty("test.resources.path")
            ?: throw IllegalStateException("test.resources.path system property not set")
        
        val testDataDir = File(testResourcesPath, "test-data")
        Assertions.assertTrue(testDataDir.exists(), "Test data directory not found at: ${testDataDir.absolutePath}")
        
        File(testDataDir, "Aggregator.groovy").copyTo(dslDir.resolve("Aggregator.groovy").toFile())
        File(testDataDir, "TestComponent.groovy").copyTo(dslDir.resolve("TestComponent.groovy").toFile())
        File(testDataDir, "test.kts").copyTo(dslDir.resolve("test.kts").toFile())
        
        return dslDir
    }
    
    private fun findRandomPort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }
    
    private fun waitForHealth(port: Int, timeoutSeconds: Int): Boolean {
        val endTime = System.currentTimeMillis() + (timeoutSeconds * 1000)
        val healthUrl = URI("http://localhost:$port/actuator/health").toURL()
        
        log.info("Waiting for health check at {}", healthUrl)
        
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
