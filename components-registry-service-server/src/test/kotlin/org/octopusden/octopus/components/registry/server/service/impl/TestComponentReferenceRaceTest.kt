package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DocLinkRequest
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * SYS-099 race: "flag A" and "reference A from a non-test B" in concurrent transactions. Optimistic
 * versions sit on different rows, so without a lock both would commit. The reference check row-locks A,
 * so the second writer waits for the first to commit and then sees its write. Runs on PostgreSQL
 * (Testcontainers) with the schema built by Flyway (V1..), whose row-lock semantics production relies on.
 */
@Tag("integration")
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db-validate")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class TestComponentReferenceRaceTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var service: ComponentManagementService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        val testResourcesPath = Paths.get(TestComponentReferenceRaceTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun create(name: String): ComponentDetailResponse =
        objectMapper.readValue(
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name","componentOwner":"owner1","baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString,
            ComponentDetailResponse::class.java,
        )

    @Test
    @DisplayName("SYS-099: a doc link to a component being flagged concurrently waits for the flag and is rejected")
    fun `SYS-099 concurrent flag and reference serialize on the target row`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val a = create("test-sys099-race-a-$suffix")
        val b = create("sys099-race-b-$suffix")

        val flagged = CountDownLatch(1)
        val release = CountDownLatch(1)
        // T1: flag A and keep the transaction open.
        val t1 =
            CompletableFuture.runAsync {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    service.updateComponent(a.id, ComponentUpdateRequest(version = a.version, testComponent = true))
                    flagged.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
        assertTrue(flagged.await(30, TimeUnit.SECONDS), "T1 did not flag A")

        // T2: link B's doc to A while T1 is still open. It must block on A's row, not commit past it.
        val t2 =
            CompletableFuture.runAsync {
                service.updateComponent(
                    b.id,
                    ComponentUpdateRequest(version = b.version, docs = listOf(DocLinkRequest(docComponentKey = a.name))),
                )
            }
        assertThrows<TimeoutException>("T2 committed while T1 still held A") { t2.get(BLOCKED_PROBE_SECONDS, TimeUnit.SECONDS) }

        release.countDown()
        t1.get(30, TimeUnit.SECONDS)
        val failure = assertThrows<ExecutionException> { t2.get(30, TimeUnit.SECONDS) }
        assertTrue(failure.cause is IllegalArgumentException, "expected the reference rule, got ${failure.cause}")
        assertTrue(failure.cause!!.message!!.startsWith("docs: ")) { failure.cause!!.message }
    }

    companion object {
        // How long T2 must stay blocked on A's row; generous so a slow CI agent cannot let it look "done".
        private const val BLOCKED_PROBE_SECONDS = 3L

        // Same wiring as V10GenericArtifactConstraintIntegrationTest / ComponentTeamcityValidationEmbeddingIntegrationTest:
        // a real PostgreSQL 16 whose schema Flyway builds from V1.. (test-db-validate: Flyway on, ddl-auto=validate).
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
