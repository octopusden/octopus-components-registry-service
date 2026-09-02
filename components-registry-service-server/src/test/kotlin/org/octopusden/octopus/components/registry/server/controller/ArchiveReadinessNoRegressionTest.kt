package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Proves the archive-readiness gate is genuinely additive: `deleteComponent`,
 * `updateComponent` (setting `archived=true`), and `getArchiveReadiness` itself all still
 * behave exactly as before this feature existed. No external system (VCS/TeamCity/Jira
 * base-url) is configured in `application-common.yml` / `application-ft-db.yml` for this test
 * context, and no `archive-readiness.*` properties are set either — so if any write path were
 * to newly consult [org.octopusden.octopus.components.registry.server.service.archivereadiness.ArchiveReadinessService],
 * readiness would resolve UNKNOWN everywhere and (were it wired as a blocking gate) the write
 * would fail. It must not: these write paths never call readiness at all.
 *
 * `@ActiveProfiles("common", "ft-db")` is required, not incidental: a bare `@SpringBootTest`
 * with no active profile retries Spring Cloud Config's bootstrap against an unreachable server
 * for 12+ hours (confirmed via thread dump on this branch). `bootstrap-ft-db.yml` disables that
 * retry loop. Do not remove or weaken this annotation.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
class ArchiveReadinessNoRegressionTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun createComponent(name: String): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","displayName":"$name","componentOwner":"owner",
                            |"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}
                            """.trimMargin(),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun versionOf(id: String): Long =
        objectMapper
            .readTree(
                mvc
                    .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                    .andReturn()
                    .response.contentAsString,
            )["version"]
            .asLong()

    @Test
    @DisplayName("soft delete works without consulting readiness")
    fun softDeleteWorksWithoutConsultingReadiness() {
        val id = createComponent("noreg-delete-${System.nanoTime()}")
        // No external system configured -> if readiness were consulted it would report UNKNOWN.
        // The delete must succeed regardless: this write path never calls readiness.
        mvc
            .perform(delete("/rest/api/4/components/$id").with(adminJwt()))
            .andExpect(status().isNoContent)
    }

    @Test
    @DisplayName("update setting archived true works without consulting readiness")
    fun updateSettingArchivedTrueWorksWithoutConsultingReadiness() {
        val id = createComponent("noreg-update-${System.nanoTime()}")
        val version = versionOf(id)
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":$version,"archived":true}"""),
            ).andExpect(status().isOk)
    }

    @Test
    @DisplayName("requesting readiness does not archive the component")
    fun requestingReadinessDoesNotArchiveTheComponent() {
        val id = createComponent("noreg-readonly-${System.nanoTime()}")
        mvc
            .perform(get("/rest/api/4/components/$id/archive-readiness").with(adminJwt()))
            .andExpect(status().isOk)
        // Component must still be non-archived: reading readiness is a read-only pre-flight,
        // never a side-effecting call.
        val detail =
            objectMapper.readTree(
                mvc
                    .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                    .andReturn()
                    .response.contentAsString,
            )
        assert(!detail["archived"].asBoolean()) { "readiness must not archive the component" }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths
                    .get(ArchiveReadinessNoRegressionTest::class.java.getResource("/expected-data")!!.toURI())
                    .parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
