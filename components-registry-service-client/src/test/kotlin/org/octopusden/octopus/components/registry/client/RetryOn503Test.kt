package org.octopusden.octopus.components.registry.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import feign.RetryableException
import feign.Retryer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class RetryOn503Test {
    private lateinit var server: HttpServer
    private lateinit var client: ComponentsRegistryServiceClient
    private lateinit var urlProvider: ClassicComponentsRegistryServiceClientUrlProvider
    private val callCount = AtomicInteger(0)

    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.start()
        val port = server.address.port
        urlProvider =
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl() = "http://localhost:$port"
            }
        client = ClassicComponentsRegistryServiceClient(urlProvider)
    }

    @AfterEach
    fun teardown() {
        server.stop(0)
        callCount.set(0)
    }

    private fun stubServiceStatus(failTimes: Int) = stubServiceStatus { attempt -> attempt <= failTimes }

    private fun stubServiceStatus(failsOn: (attempt: Int) -> Boolean) {
        val body = """{"cacheUpdatedAt":0,"serviceMode":"FS","versionControlRevision":"abc"}"""
        server.createContext("/rest/api/2/components-registry/service/status") { exchange ->
            val attempt = callCount.incrementAndGet()
            if (failsOn(attempt)) {
                exchange.sendResponseHeaders(503, -1)
            } else {
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
    }

    @Test
    fun `succeeds after two 503s`() {
        stubServiceStatus(failTimes = 2)
        val status = client.getServiceStatus()
        assertEquals(3, callCount.get(), "Should have taken 3 attempts (2 retries + 1 success)")
        assertEquals("FS", status.serviceMode.name)
    }

    @Test
    fun `fails with RetryableException when all retries exhausted`() {
        stubServiceStatus(failTimes = Int.MAX_VALUE)
        assertThrows(RetryableException::class.java) {
            client.getServiceStatus()
        }
        // Retryer.Default(100, 1000, 5): attempt starts at 1, throws when attempt >= 5 → 5 total HTTP calls
        assertEquals(5, callCount.get(), "Should have tried 5 times (1 initial + 4 retries)")
    }

    @Test
    @DisplayName("SYS-103: caller-supplied retryer outlasts the default")
    fun `SYS-103 caller-supplied retryer outlasts the default`() {
        // More 503s than the default retryer tolerates: with it this call would fail.
        stubServiceStatus(failTimes = 6)
        val patientClient =
            ClassicComponentsRegistryServiceClient(
                urlProvider,
                ClassicComponentsRegistryServiceClient.configureObjectMapper(ObjectMapper()),
                Retryer.Default(10, 10, 10),
            )
        val status = patientClient.getServiceStatus()
        assertEquals(7, callCount.get(), "Should have taken 7 attempts (6 retries + 1 success)")
        assertEquals("FS", status.serviceMode.name)
    }

    @Test
    @DisplayName("SYS-103: retryer state is not shared between requests")
    fun `SYS-103 retryer state is not shared between requests`() {
        // Feign clones the retryer per request: a budget exhausted by one call must not
        // shorten the next one. Budget is 3 attempts; calls 1-3 fail, so the first request
        // gives up. Calls 4-5 fail and 6 succeeds, which the second request reaches only
        // with a fresh budget of 3.
        stubServiceStatus { attempt -> attempt <= 5 }
        val shortBudgetClient =
            ClassicComponentsRegistryServiceClient(
                urlProvider,
                ClassicComponentsRegistryServiceClient.configureObjectMapper(ObjectMapper()),
                Retryer.Default(10, 10, 3),
            )
        assertThrows(RetryableException::class.java) { shortBudgetClient.getServiceStatus() }
        assertEquals(3, callCount.get())
        assertEquals("FS", shortBudgetClient.getServiceStatus().serviceMode.name)
        assertEquals(6, callCount.get())
    }
}
