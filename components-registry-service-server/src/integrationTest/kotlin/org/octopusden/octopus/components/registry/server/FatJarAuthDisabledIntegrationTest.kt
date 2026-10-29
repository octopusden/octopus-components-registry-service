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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tests the `auth-server.disabled` opt-out introduced for FT environments without a Keycloak.
 *
 * Two contracts are locked in here:
 *  * [`fat jar with auth-server disabled starts and gates writes`] — disabled mode lets v1-v3 and
 *    v4 reads through anonymously but `@PreAuthorize` still rejects v4 writes (403). If method
 *    security ever silently breaks in disabled mode, FT pipelines would otherwise turn the
 *    registry into an open delete surface.
 *  * [`fat jar without auth-server config or disabled flag fails fast`] — production default
 *    (`matchIfMissing = true` on `WebSecurityConfig`) must keep crashing at startup so a missing
 *    `AUTH_SERVER_URL` env var in prod can never silently fall through to anonymous mode.
 */
class FatJarAuthDisabledIntegrationTest {
    private val log = LoggerFactory.getLogger(FatJarAuthDisabledIntegrationTest::class.java)

    @Test
    @Timeout(120)
    fun `fat jar with auth-server disabled starts and gates writes`(
        @TempDir tempDir: Path,
    ) {
        val port = findRandomPort()
        val process = startFatJar(
            tempDir,
            port,
            extraJvmArgs = listOf("-Dauth-server.disabled=true"),
        )

        try {
            Assertions.assertTrue(
                waitForHealth(port, timeoutSeconds = 90),
                "Application did not become healthy within timeout in auth-disabled mode. " +
                    "Captured output should explain why; check stdout above.",
            )

            // v1-v3: legacy anonymous read. Deterministic — does not depend on imported fixture data.
            assertHttpStatus("GET", "http://localhost:$port/rest/api/2/components-registry/service/ping", 200)

            // The KEY assertion: a protected v4 write must be gated by @PreAuthorize even with
            // permitAll() at the filter chain, because AnonymousSecurityConfig keeps method
            // security enabled via @EnableMethodSecurity. ROLE_ANONYMOUS lacks DELETE_COMPONENTS,
            // so this returns 403 — not 204, not 500. If method security ever silently breaks in
            // disabled mode, this assertion catches it.
            //
            // Use a syntactically valid UUID; an invalid {id} would 400 at argument binding before
            // method-security runs and would not actually exercise the contract.
            //
            // We deliberately do NOT assert on a v4 GET (e.g. /rest/api/4/components) here: the
            // integration-test profile uses an embedded H2 with no migrations applied, so v4 list
            // endpoints can fail with 500 from the data layer regardless of auth state. That is
            // outside the scope of this test, which is about the security wiring.
            assertHttpStatus(
                "DELETE",
                "http://localhost:$port/rest/api/4/components/00000000-0000-0000-0000-000000000000",
                403,
            )
        } finally {
            stopProcess(process)
        }
    }

    @Test
    @Timeout(60)
    fun `fat jar without auth-server config or disabled flag fails fast`(
        @TempDir tempDir: Path,
    ) {
        val port = findRandomPort()
        val output = StringBuilder()
        // No -Dauth-server.url, -Dauth-server.realm, or -Dauth-server.disabled — pure prod-default.
        val process = startFatJar(tempDir, port, extraJvmArgs = emptyList(), capturedOutput = output)

        try {
            // 30s is well above CRS's normal context-refresh time on a healthy run. The contract
            // is "fail before health goes green", so we explicitly assert that health never comes
            // up. If it does, matchIfMissing = true was relaxed and prod is unprotected.
            val becameHealthy = waitForHealth(port, timeoutSeconds = 30)
            Assertions.assertFalse(
                becameHealthy,
                "Application went healthy without auth-server config — the prod fail-fast " +
                    "contract is broken. Inspect WebSecurityConfig.@ConditionalOnProperty's " +
                    "matchIfMissing and AnonymousSecurityConfig's condition.",
            )

            // Process must have exited (the eager OIDC discovery in AuthServerClient.init{} throws
            // synchronously during context refresh, which Spring Boot translates to exit non-zero).
            Assertions.assertTrue(
                process.waitFor(15, TimeUnit.SECONDS),
                "Process did not exit within 15s after health timeout. Likely hung instead of " +
                    "fail-fast — check whether the OIDC discovery exception is being swallowed.",
            )
            Assertions.assertNotEquals(0, process.exitValue(), "Expected non-zero exit code, got 0.")

            val logContent = synchronized(output) { output.toString() }
            val matchedExpectedError = logContent.contains("OAuth2AuthenticationException") ||
                logContent.contains("BeanInitializationException")
            Assertions.assertTrue(
                matchedExpectedError,
                "Expected OAuth2AuthenticationException or BeanInitializationException in startup " +
                    "log, but neither was found. The fail-fast trigger may have shifted to another " +
                    "exception type — update this assertion or fix the regression.",
            )
        } finally {
            stopProcess(process)
        }
    }

    private fun startFatJar(
        tempDir: Path,
        port: Int,
        extraJvmArgs: List<String>,
        capturedOutput: StringBuilder = StringBuilder(),
    ): Process {
        val fatJarPath = System.getProperty("fatJar.path")
            ?: throw IllegalStateException("fatJar.path system property not set")
        val fatJar = File(fatJarPath)
        Assertions.assertTrue(fatJar.exists(), "Fat jar not found at: $fatJarPath")

        val testResourcesPath = System.getProperty("test.resources.path")
            ?: throw IllegalStateException("test.resources.path system property not set")
        val profilePath = File(testResourcesPath, "application-integration-test.yml")
        Assertions.assertTrue(profilePath.exists(), "Integration test profile not found at: ${profilePath.absolutePath}")

        val dslDir = setupDslFiles(tempDir, testResourcesPath)
        val projectRegistryFile = tempDir.resolve("project-registry.json")
        Files.writeString(projectRegistryFile, "{}")

        val javaExecutable = File(System.getProperty("java.home"), "bin/java")
        val command = mutableListOf(
            javaExecutable.absolutePath,
            "-Dspring.profiles.active=integration-test",
            "-Dspring.config.additional-location=file:${profilePath.absolutePath}",
            "-Dcomponents-registry.groovy-path=${dslDir.toAbsolutePath()}",
            "-Dcomponents-registry.work-dir=${tempDir.toAbsolutePath()}",
            "-Dcomponents-registry.project-registry-path=${projectRegistryFile.toAbsolutePath()}",
            "-Dserver.port=$port",
        )
        command.addAll(extraJvmArgs)
        command.add("-jar")
        command.add(fatJar.absolutePath)

        log.info("Starting application with extra args {} on port {}", extraJvmArgs, port)
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        val process = processBuilder.start()

        thread(start = true, isDaemon = true) {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    synchronized(capturedOutput) { capturedOutput.appendLine(line) }
                    log.info("App output: {}", line)
                }
            }
        }

        return process
    }

    private fun stopProcess(process: Process) {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
    }

    private fun setupDslFiles(
        tempDir: Path,
        testResourcesPath: String,
    ): Path {
        val dslDir = tempDir.resolve("dsl")
        Files.createDirectories(dslDir)
        val testDataDir = File(testResourcesPath, "test-data")
        Assertions.assertTrue(testDataDir.exists(), "Test data directory not found at: ${testDataDir.absolutePath}")
        File(testDataDir, "Aggregator.groovy").copyTo(dslDir.resolve("Aggregator.groovy").toFile())
        File(testDataDir, "TestComponent.groovy").copyTo(dslDir.resolve("TestComponent.groovy").toFile())
        File(testDataDir, "TestComponent.kts").copyTo(dslDir.resolve("TestComponent.kts").toFile())
        return dslDir
    }

    private fun findRandomPort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitForHealth(
        port: Int,
        timeoutSeconds: Int,
    ): Boolean {
        val endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        val healthUrl = URI("http://localhost:$port/actuator/health").toURL()
        while (System.currentTimeMillis() < endTime) {
            try {
                with(healthUrl.openConnection() as HttpURLConnection) {
                    requestMethod = "GET"
                    connectTimeout = 1000
                    readTimeout = 1000
                    connect()
                    if (responseCode == 200) return true
                }
            } catch (_: Exception) {
                Thread.sleep(1000)
            }
        }
        return false
    }

    private fun assertHttpStatus(
        method: String,
        url: String,
        expectedStatus: Int,
    ) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            Assertions.assertEquals(
                expectedStatus,
                connection.responseCode,
                "$method $url: expected $expectedStatus, got ${connection.responseCode}",
            )
        } finally {
            connection.disconnect()
        }
    }
}
