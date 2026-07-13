package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ServiceEventEntity
import org.octopusden.octopus.components.registry.server.repository.ServiceEventRepository
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * SYS-060: `GET /rest/api/4/admin/service-events` is IMPORT_DATA-gated, returns the
 * journal newest-first, and honours the optional filters.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class ServiceEventControllerV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var repository: ServiceEventRepository

    init {
        val testResourcesPath =
            java.nio.file.Paths.get(ServiceEventControllerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeEach
    fun seed() {
        // Clear the STARTUP row the app writes on boot (and any auto-migrate rows) so
        // assertions see only what this test inserts.
        repository.deleteAll()
        val now = Instant.now()
        repository.save(
            row(ServiceEventType.TEAMCITY_RESYNC, ServiceEventStatus.COMPLETED, now.minus(2, ChronoUnit.HOURS)),
        )
        repository.save(
            row(ServiceEventType.MIGRATION_COMPONENTS, ServiceEventStatus.FAILED, now.minus(1, ChronoUnit.HOURS)),
        )
    }

    @Test
    fun `SYS-060 lists events newest-first for an IMPORT_DATA caller`() {
        mvc
            .perform(get("/rest/api/4/admin/service-events").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            // Newest (migration, 1h ago) first.
            .andExpect(jsonPath("$.content[0].eventType").value("MIGRATION_COMPONENTS"))
            .andExpect(jsonPath("$.content[0].status").value("FAILED"))
            .andExpect(jsonPath("$.content[1].eventType").value("TEAMCITY_RESYNC"))
    }

    @Test
    fun `SYS-060 filters by status`() {
        mvc
            .perform(get("/rest/api/4/admin/service-events?status=FAILED").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].eventType").value("MIGRATION_COMPONENTS"))
    }

    @Test
    fun `response carries the derived SYSTEM category for operational events`() {
        mvc
            .perform(get("/rest/api/4/admin/service-events").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].category").value("SYSTEM"))
            .andExpect(jsonPath("$.content[1].category").value("SYSTEM"))
    }

    @Test
    fun `filters by category — USER returns only user events, SYSTEM only operational`() {
        // Add a user-event (onboarding view) alongside the two seeded system events.
        repository.save(
            row(ServiceEventType.ONBOARDING_VIDEO_VIEW, ServiceEventStatus.COMPLETED, Instant.now()),
        )

        mvc
            .perform(get("/rest/api/4/admin/service-events?category=USER").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].eventType").value("ONBOARDING_VIDEO_VIEW"))
            .andExpect(jsonPath("$.content[0].category").value("USER"))

        mvc
            .perform(get("/rest/api/4/admin/service-events?category=SYSTEM").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
    }

    @Test
    fun `SYS-060 read requires IMPORT_DATA (editor is forbidden)`() {
        mvc
            .perform(get("/rest/api/4/admin/service-events").with(editorJwt()))
            .andExpect(status().isForbidden)
    }

    private fun row(
        type: ServiceEventType,
        status: ServiceEventStatus,
        startedAt: Instant,
    ) = ServiceEventEntity(
        eventType = type.name,
        status = status.name,
        source = ServiceEventSource.CRS.wire,
        triggeredBy = "alice",
        startedAt = startedAt,
        finishedAt = startedAt.plusSeconds(5),
    )
}
