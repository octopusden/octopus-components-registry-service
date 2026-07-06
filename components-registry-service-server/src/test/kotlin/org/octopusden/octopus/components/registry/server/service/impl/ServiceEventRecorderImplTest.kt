package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.repository.ServiceEventRepository
import org.octopusden.octopus.components.registry.server.service.ServiceEventRecorder
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration
import java.time.Instant

/**
 * SYS-060: DB-backed behaviour of the service-event recorder — RUNNING→terminal
 * transition in place, terminal-insert fallback when no RUNNING row exists,
 * startup reconciliation, and retention prune.
 */
@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
@Tag("integration")
class ServiceEventRecorderImplTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var recorder: ServiceEventRecorder

    @Autowired
    private lateinit var repository: ServiceEventRepository

    init {
        val testResourcesPath =
            java.nio.file.Paths.get(ServiceEventRecorderImplTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeEach
    fun clean() {
        repository.deleteAll()
    }

    @Test
    fun `SYS-060 recordStart then recordFinish transitions the running row in place`() {
        recorder.recordStart(ServiceEventType.TEAMCITY_RESYNC, ServiceEventSource.CRS, "alice", "job-1", "running")
        val running = repository.findAll().single()
        assertEquals(ServiceEventStatus.RUNNING.name, running.status)
        assertNull(running.finishedAt)

        recorder.recordFinish(
            ServiceEventType.TEAMCITY_RESYNC, ServiceEventSource.CRS, "alice", "job-1",
            ServiceEventStatus.COMPLETED, "done", mapOf("updated" to 2),
        )

        val row = repository.findAll().single() // still ONE row — transitioned, not appended
        assertEquals(ServiceEventStatus.COMPLETED.name, row.status)
        assertNotNull(row.finishedAt)
        assertEquals(2, row.detail?.get("updated"))
    }

    @Test
    fun `SYS-060 recordFinish without a running row inserts a terminal row`() {
        recorder.recordFinish(
            ServiceEventType.MIGRATION_COMPONENTS, ServiceEventSource.CRS, "alice", "orphan-job",
            ServiceEventStatus.FAILED, "boom", mapOf("errorMessage" to "x"),
        )

        val row = repository.findAll().single()
        assertEquals(ServiceEventStatus.FAILED.name, row.status)
        assertEquals("orphan-job", row.correlationId)
        assertNotNull(row.finishedAt)
    }

    @Test
    fun `SYS-060 reconcileOrphanedRunning flips crs running rows to failed`() {
        recorder.recordStart(ServiceEventType.MIGRATION_HISTORY, ServiceEventSource.CRS, "system", "stuck", "running")

        val reconciled = recorder.reconcileOrphanedRunning(ServiceEventSource.CRS)

        assertEquals(1, reconciled)
        val row = repository.findAll().single()
        assertEquals(ServiceEventStatus.FAILED.name, row.status)
        assertEquals("interrupted by restart", row.summary)
        assertNotNull(row.finishedAt)
    }

    @Test
    fun `SYS-060 prune deletes rows started before the cutoff`() {
        recorder.recordInstant(
            type = ServiceEventType.STARTUP, source = ServiceEventSource.CRS, triggeredBy = "system",
            startedAt = Instant.now().minus(Duration.ofDays(120)),
        )
        recorder.recordInstant(
            type = ServiceEventType.STARTUP, source = ServiceEventSource.CRS, triggeredBy = "system",
            startedAt = Instant.now(),
        )

        val pruned = recorder.prune(Instant.now().minus(Duration.ofDays(90)))

        assertEquals(1, pruned)
        assertEquals(1, repository.findAll().size)
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
