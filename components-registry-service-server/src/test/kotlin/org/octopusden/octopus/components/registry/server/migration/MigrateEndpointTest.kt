package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.dto.v4.MigrationJobResponse
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * End-to-end smoke for `POST /admin/migrate` against a real Postgres testcontainer.
 *
 * The migration is async in production: the controller returns 202 immediately and the
 * thread pool runs the work in the background. To keep this test deterministic we swap
 * the production [TaskExecutor] for a [SyncTaskExecutor] via [SyncMigrationExecutorConfig]
 * — the migration body completes inline before the POST returns, so the response carries
 * a COMPLETED [MigrationJobResponse] with `result` populated and the test can assert on
 * the same shape it did before async ([MigrationJobResponse.result.components.total],
 * etc.) without waiting on or polling a background thread.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@Import(MigrateEndpointTest.SyncMigrationExecutorConfig::class)
@ActiveProfiles("common", "test-db")
class MigrateEndpointTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(MigrateEndpointTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    fun `POST migrate returns 202 + COMPLETED job with full result body once the sync executor finishes`() {
        val body =
            mvc
                .perform(post("/rest/api/4/admin/migrate").with(adminJwt()).accept(APPLICATION_JSON))
                .andExpect(status().isAccepted)
                .andReturn()
                .response.contentAsString

        val job: MigrationJobResponse = objectMapper.readValue(body)

        // SyncTaskExecutor: the migration finished before the response was assembled,
        // so the JobResponse already carries the COMPLETED state and the populated
        // result block. The assertions below mirror the pre-async test contract.
        assertEquals(JobState.COMPLETED, job.state)
        assertNotNull(job.finishedAt)
        val result = job.result
        assertNotNull(result, "FullMigrationResult must be populated once state == COMPLETED")
        // !! is safe here — the assertNotNull above would have aborted the test.
        // Kotlin's flow-sensitive nullability doesn't see through assertNotNull, so we
        // re-bind to a non-null local for the remaining assertions.
        val populated = result!!
        // defaults were migrated
        assertFalse(populated.defaults.isEmpty(), "defaults map must not be empty")
        assertTrue(populated.defaults.containsKey("buildSystem"), "buildSystem must be present in defaults")
        // components were migrated
        assertTrue(populated.components.total > 0, "Expected components to be migrated")
        assertEquals(
            0,
            populated.components.failed,
            "Expected no migration failures: ${populated.components.results.filter { !it.success }}",
        )
        assertEquals(populated.components.migrated + populated.components.skipped, populated.components.total)

        // phase must be cleared on COMPLETED. While RUNNING the field carries
        // "DEFAULTS" / "COMPONENTS" so the SPA renders informative labels, but
        // by the time we see COMPLETED there is no active sub-phase to report.
        // Clearing it explicitly (rather than leaving e.g. "COMPONENTS") avoids
        // the SPA mistakenly rendering a phase label next to the result tiles.
        assertEquals(null, job.phase, "phase must be null on COMPLETED")
    }

    /**
     * Replaces the production single-thread [TaskExecutor] (defined in
     * MigrationExecutorConfig) with a synchronous one so the migration runs inline
     * on the request thread. The production bean is `@ConditionalOnMissingBean`,
     * so when this @TestConfiguration registers the `migrationExecutor` slot first,
     * the production one quietly steps aside.
     */
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
