package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
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
import org.springframework.context.annotation.Primary
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
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

/**
 * Endpoint-shape smoke test for `POST /admin/migrate-history` + `GET
 * /admin/migrate-history/job`. Heavier end-to-end coverage with a real JGit
 * fixture lives in [MigrateHistoryIntegrationTest]; this file pins the wire
 * contract that the SPA depends on (state field, populated `result` block on
 * COMPLETED, GET /job returning the same shape) without paying the JGit
 * fixture cost on every CI run.
 *
 * Why a hand-rolled stub instead of @MockBean: Mockito's `any(...)` returns
 * null at the call site, which trips the JVM null-check on Kotlin's
 * non-nullable `listener: HistoryImportProgressListener` parameter before the
 * proxy intercepts. The same trap is documented in MigrationJobServiceImplTest
 * — both files dodge it via a stub registered through @TestConfiguration.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@Import(MigrateHistoryAsyncEndpointTest.AsyncEndpointTestConfig::class)
@ActiveProfiles("common", "test-db")
class MigrateHistoryAsyncEndpointTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /**
     * Test-controlled holder that the stub bean reads from. Each test sets
     * the canned [HistoryImportResult] before driving a POST so the bean
     * returns whatever the test wants.
     */
    @Autowired
    private lateinit var stubResult: AtomicReference<HistoryImportResult>

    init {
        // ComponentsRegistryProperties has a property `groovy-path` whose
        // default value references ${COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR}.
        // Without it set, ApplicationContext fails to bind. Mirror the pattern
        // from MigrateEndpointTest / AdminControllerV4SecurityTest: point at
        // the test-resources dir so the placeholder resolves.
        val testResourcesPath =
            Paths.get(MigrateHistoryAsyncEndpointTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    fun `POST migrate-history returns 202 + COMPLETED body once the sync executor finishes`() {
        stubResult.set(
            HistoryImportResult(
                targetRef = "refs/tags/test-1.0",
                targetSha = "abc1234567890",
                processedCommits = 42,
                skippedNoGroovy = 3,
                skippedParseError = 1,
                skippedUnknownNames = 0,
                auditRecords = 99,
                durationMs = 500,
            ),
        )

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
        assertEquals(stubResult.get(), job.result)
        assertNull(job.currentSha, "currentSha must be cleared once the run is done")
    }

    @Test
    fun `JSON wire shape includes kind=job discriminator`() {
        // P2 review fix: the SPA branches on `kind === 'conflict'` to
        // distinguish same-kind 409 attach from cross-kind 409 conflict.
        // Jackson serializes the data-class default value `kind = "job"`,
        // but if a future refactor turns the response into an interface
        // and an implementation forgets the field, no test would fire.
        // This test pins the wire contract.
        stubResult.set(
            HistoryImportResult(
                targetRef = "refs/tags/test-3.0",
                targetSha = "kind-discriminator-test",
                processedCommits = 0,
                skippedNoGroovy = 0,
                skippedParseError = 0,
                skippedUnknownNames = 0,
                auditRecords = 0,
                durationMs = 0,
            ),
        )
        val body =
            mvc
                .perform(post("/rest/api/4/admin/migrate-history").with(adminJwt()).accept(APPLICATION_JSON))
                .andExpect(status().isAccepted)
                .andReturn().response.contentAsString
        // Asserting on the raw JSON (not the deserialized DTO) — the contract
        // is "the field is present on the wire", and DTO deserialization
        // would happily fall back to the data-class default if the field
        // were missing in the JSON.
        org.junit.jupiter.api.Assertions.assertTrue(
            body.contains("\"kind\":\"job\""),
            "Response JSON must include the 'kind' discriminator. Body was: $body",
        )
    }

    @Test
    fun `POST then GET migrate-history-job returns the same job shape`() {
        stubResult.set(
            HistoryImportResult(
                targetRef = "refs/tags/test-2.0",
                targetSha = "deadbeef",
                processedCommits = 5,
                skippedNoGroovy = 0,
                skippedParseError = 0,
                skippedUnknownNames = 0,
                auditRecords = 12,
                durationMs = 100,
            ),
        )

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

    /**
     * @TestConfiguration replaces both the production migrationExecutor (with
     * a SyncTaskExecutor so each POST runs the import inline) AND the real
     * GitHistoryImportService (with a hand-rolled stub that returns whatever
     * the test stuffs into [stubResult]). @Primary on the service bean wins
     * over the production @Service-annotated impl during context wiring.
     */
    @TestConfiguration
    open class AsyncEndpointTestConfig {
        @Bean("migrationExecutor")
        open fun syncMigrationExecutor(): TaskExecutor = SyncTaskExecutor()

        @Bean
        open fun stubResult(): AtomicReference<HistoryImportResult> =
            AtomicReference(
                HistoryImportResult(
                    targetRef = "",
                    targetSha = "",
                    processedCommits = 0,
                    skippedNoGroovy = 0,
                    skippedParseError = 0,
                    skippedUnknownNames = 0,
                    auditRecords = 0,
                    durationMs = 0,
                ),
            )

        @Bean
        @Primary
        open fun stubGitHistoryImportService(stubResult: AtomicReference<HistoryImportResult>): GitHistoryImportService =
            object : GitHistoryImportService {
                override fun importHistory(
                    toRef: String?,
                    reset: Boolean,
                    listener: HistoryImportProgressListener,
                ): HistoryImportResult = stubResult.get()
            }
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
