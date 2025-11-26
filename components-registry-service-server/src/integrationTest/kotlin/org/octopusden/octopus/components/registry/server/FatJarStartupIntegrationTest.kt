package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Integration test that verifies the fat jar can start successfully.
 * This test specifically checks for the Kotlin stdlib issue that occurs
 * when running as a Spring Boot fat jar in production environments.
 * 
 * This test is designed to fail on branches without the fix for:
 * - "Unable to find kotlin stdlib" error
 * - "Cannot access script base class" error
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
        println("Fat jar size: ${fatJar.length() / 1024 / 1024} MB")
        
        // Copy test DSL files from resources to temp directory
        val testResourcesPath = System.getProperty("test.resources.path")
            ?: throw IllegalStateException("test.resources.path system property not set")
        
        val resourcesDir = File(testResourcesPath)
        Assertions.assertTrue(resourcesDir.exists() && resourcesDir.isDirectory, 
            "Test resources directory not found at: $testResourcesPath")
        
        val dslDir = tempDir.resolve("dsl")
        Files.createDirectories(dslDir)
        
        // Copy resource files
        File(resourcesDir, "Aggregator.groovy").copyTo(dslDir.resolve("Aggregator.groovy").toFile())
        File(resourcesDir, "TestComponent.groovy").copyTo(dslDir.resolve("TestComponent.groovy").toFile())
        File(resourcesDir, "test.kts").copyTo(dslDir.resolve("test.kts").toFile())
        
        // Create dummy project registry
        val projectRegistryFile = tempDir.resolve("project-registry.json")
        Files.writeString(projectRegistryFile, "{}")
        
        println("Test DSL directory: ${dslDir.toAbsolutePath()}")
        
        // Find java executable
        val javaHome = System.getProperty("java.home")
        val javaExecutable = File(javaHome, "bin/java")
        Assertions.assertTrue(javaExecutable.exists(), "Java executable not found at: ${javaExecutable.absolutePath}")
        
        // Build command
        val command = mutableListOf(
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
            "-Dserver.port=0", // Use random available port
            "-jar",
            fatJar.absolutePath
        )
        
        println("Starting application...")
        
        val processBuilder = ProcessBuilder(command)
        processBuilder.redirectErrorStream(true)
        
        val process = processBuilder.start()
        
        val output = StringBuilder()
        var startupSuccessful = false
        var kotlinStdlibError = false
        var scriptBaseClassError = false
        
        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val startTime = System.currentTimeMillis()
            val maxWaitTime = 90_000 // 90 seconds
            
            var line: String?
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                line = reader.readLine()
                if (line == null) {
                    // Process ended
                    break
                }
                
                output.appendLine(line)
                println(line) // Echo to test output
                
                // Check for startup success
                if (line.contains("Started ComponentRegistryServiceApplication")) {
                    println("\n✅ Application started successfully!")
                    startupSuccessful = true
                    break
                }
                
                // Check for Kotlin stdlib error
                if (line.contains("kotlin stdlib", ignoreCase = true) && 
                    line.contains("please specify it explicitly", ignoreCase = true)) {
                    println("\n❌ Found Kotlin stdlib error!")
                    kotlinStdlibError = true
                }
                
                // Check for script base class error
                if (line.contains("Cannot access script base class", ignoreCase = true)) {
                    println("\n❌ Found script base class error!")
                    scriptBaseClassError = true
                }
                
                // Check for other fatal errors
                if (line.contains("APPLICATION FAILED TO START", ignoreCase = true)) {
                    println("\n❌ Application failed to start!")
                    break
                }
            }
            
            // Give a moment to check if process is still alive
            Thread.sleep(2000)
            
        } finally {
            // Always cleanup the process
            if (process.isAlive) {
                println("Shutting down application...")
                process.destroy()
                process.waitFor(10, TimeUnit.SECONDS)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        }
        
        val exitCode = process.waitFor()
        
        println("\n=== Test Results ===")
        println("Startup successful: $startupSuccessful")
        println("Kotlin stdlib error: $kotlinStdlibError")
        println("Script base class error: $scriptBaseClassError")
        println("Exit code: $exitCode")
        
        // Save full output to file for debugging
        val logFile = File(System.getProperty("java.io.tmpdir"), "fat-jar-integration-test-${System.currentTimeMillis()}.log")
        logFile.writeText(output.toString())
        println("Full log saved to: ${logFile.absolutePath}")
        
        // Assertions
        Assertions.assertFalse(
            kotlinStdlibError,
            "Application failed with 'kotlin stdlib' error. This indicates the fix is not working correctly.\n" +
            "Check log: ${logFile.absolutePath}"
        )
        
        Assertions.assertFalse(
            scriptBaseClassError,
            "Application failed with 'script base class' error. This indicates classpath issues.\n" +
            "Check log: ${logFile.absolutePath}"
        )
        
        Assertions.assertTrue(
            startupSuccessful,
            "Application did not start successfully within the timeout period.\n" +
            "Check log: ${logFile.absolutePath}\n" +
            "Exit code: $exitCode"
        )
        
        println("\n✅ Fat jar integration test PASSED!")
    }
}
