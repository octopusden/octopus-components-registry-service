package org.octopusden.octopus.components.registry.client

import com.sun.net.httpserver.HttpServer
import feign.RetryableException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider

class RetryOn503Test {

    private lateinit var server: HttpServer
    private lateinit var client: ComponentsRegistryServiceClient
    private val callCount = AtomicInteger(0)

    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.start()
        val port = server.address.port
        client = ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl() = "http://localhost:$port"
            }
        )
    }

    @AfterEach
    fun teardown() {
        server.stop(0)
        callCount.set(0)
    }

    private fun stubServiceStatus(failTimes: Int) {
        val body = """{"cacheUpdatedAt":0,"serviceMode":"FS","versionControlRevision":"abc"}"""
        server.createContext("/rest/api/2/components-registry/service/status") { exchange ->
            val attempt = callCount.incrementAndGet()
            if (attempt <= failTimes) {
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
}
