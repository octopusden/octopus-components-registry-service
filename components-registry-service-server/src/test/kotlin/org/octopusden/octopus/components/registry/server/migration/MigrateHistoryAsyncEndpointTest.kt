package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.dto.v4.HistoryImportResult
import org.octopusden.octopus.components.registry.server.dto.v4.HistoryMigrationJobResponse
import org.octopusden.octopus.components.registry.server.service.GitHistoryImportService
import org.octopusden.octopus.components.registry.server.service.HistoryImportProgressListener
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Endpoint-shape smoke test for `POST /admin/migrate-history` + `GET
 * /admin/migrate-history/job`. Heavier end-to-end coverage with a real JGit
 * fixture lives in [MigrateHistoryIntegrationTest]; this file pins the wire
 * contract that the SPA depends on (state field, populated `result` block on
 * COMPLETED, GET /job returning the same shape) without paying the JGit
 * fixture cost on every CI run.
 *
 * GitHistoryImportService is @MockBean'd so we don't need to set up a clone
 * source or DSL fixtures — we control what `importHistory` returns and
 * assert the controller wires the response correctly.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@Import(MigrateHistoryAsyncEndpointTest.SyncMigrationExecutorConfig::class)
@ActiveProfiles("common", "test-db")
class MigrateHistoryAsyncEndpointTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    /**
     * Mocked: the controller wires the in-memory job service which in turn
     * wires this. Replacing it with a stub returning a canned result lets us
     * assert on the response wiring without paying for git clone + DSL parse.
     */
    @MockBean
    private lateinit var gitHistoryImportService: GitHistoryImportService

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `POST migrate-history returns 202 + COMPLETED body once the sync executor finishes`() {
        val cannedResult =
            HistoryImportResult(
                targetRef = "refs/tags/test-1.0",
                targetSha = "abc1234567890",
                processedCommits = 42,
                skippedNoGroovy = 3,
                skippedParseError = 1,
                skippedUnknownNames = 0,
                auditRecords = 99,
                durationMs = 500,
            )
        `when`(
            gitHistoryImportService.importHistory(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.any(HistoryImportProgressListener::class.java),
            ),
        ).thenReturn(cannedResult)

        val body =
            mvc
                .perform(post("/rest/api/4/admin/migrate-history").with(adminJwt()).accept(APPLICATION_JSON))
                .andExpect(status().isAccepted)
                .andReturn()
                .response
                .contentAsString

        val job: HistoryMigrationJobResponse = objectMapper.readValue(body)
        // SyncTaskExecutor: by the time the response was assembled, the
        // runnable has already finished, so terminal state + populated
        // result block are observable directly.
        assertEquals(JobState.COMPLETED, job.state)
        assertNotNull(job.finishedAt)
        assertEquals(42, job.processedCommits)
        assertEquals(99, job.auditRecords)
        assertEquals(cannedResult, job.result)
        assertNull(job.currentSha, "currentSha must be cleared once the run is done")
    }

    @Test
    fun `POST then GET migrate-history-job returns the same job shape`() {
        val cannedResult =
            HistoryImportResult(
                targetRef = "refs/tags/test-2.0",
                targetSha = "deadbeef",
                processedCommits = 5,
                skippedNoGroovy = 0,
                skippedParseError = 0,
                skippedUnknownNames = 0,
                auditRecords = 12,
                durationMs = 100,
            )
        `when`(
            gitHistoryImportService.importHistory(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.any(HistoryImportProgressListener::class.java),
            ),
        ).thenReturn(cannedResult)

        val postedBody =
            mvc
                .perform(post("/rest/api/4/admin/migrate-history").with(adminJwt()).accept(APPLICATION_JSON))
                .andExpect(status().isAccepted)
                .andReturn().response.contentAsString
        val posted: HistoryMigrationJobResponse = objectMapper.readValue(postedBody)

        val gotBody =
            mvc
                .perform(get("/rest/api/4/admin/migrate-history/job").with(adminJwt()).accept(APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val got: HistoryMigrationJobResponse = objectMapper.readValue(gotBody)

        assertEquals(posted.id, got.id)
        assertEquals(posted.state, got.state)
        assertEquals(posted.result, got.result)
    }

    @TestConfiguration
    open class SyncMigrationExecutorConfig {
        @Bean("migrationExecutor")
        open fun syncMigrationExecutor(): TaskExecutor = SyncTaskExecutor()
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
