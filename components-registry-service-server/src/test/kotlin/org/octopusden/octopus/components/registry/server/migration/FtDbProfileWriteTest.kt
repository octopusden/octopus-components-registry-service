package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * SYS-027 — verifies that the ft-db profile (H2 in-memory, PostgreSQL compatibility mode)
 * supports write operations that hit entity columns declared with `columnDefinition = "jsonb"`.
 *
 * The companion read-only test `FtDbProfileTest` already proves auto-migrate populates the DB
 * and read endpoints work under ft-db; this test exercises the write path.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@Timeout(120)
class FtDbProfileWriteTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(FtDbProfileWriteTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun firstComponent(): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components").with(editorJwt()).param("page", "0").param("size", "1"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val root = objectMapper.readTree(body)
        val content = root.path("content")
        assertTrue(content.isArray && content.size() > 0, "Expected at least one component in DB under ft-db profile")
        return content[0]
    }

    private fun getComponent(id: String): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$id").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }

    @Test
    @DisplayName("SYS-027: PATCH buildConfiguration.metadata round-trips under ft-db (H2 jsonb column)")
    fun `SYS-027 PATCH buildConfiguration metadata round-trips`() {
        val summary = firstComponent()
        val id = summary["id"].asText()
        val detail = getComponent(id)
        val version = detail["version"].asLong()

        val metadataPayload =
            mapOf(
                "mavenVersion" to "3.9.5",
                "gradleVersion" to "8.5",
                "customFlag" to true,
                "retries" to 3,
                "extra" to mapOf("profile" to "ft-db", "tags" to listOf("a", "b")),
            )
        val payload =
            mapOf(
                "version" to version,
                "buildConfiguration" to mapOf("metadata" to metadataPayload),
            )

        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(payload)),
            ).andExpect(status().is2xxSuccessful)

        val updated = getComponent(id)
        val builds = updated["buildConfigurations"]
        assertNotNull(builds, "buildConfigurations should be present in response")
        assertTrue(builds.isArray && builds.size() > 0, "Expected at least one buildConfiguration after PATCH")
        val meta = builds[0]["metadata"]
        assertNotNull(meta, "buildConfigurations[0].metadata should be present")
        assertEquals("3.9.5", meta["mavenVersion"].asText(), "mavenVersion round-trip")
        assertEquals("8.5", meta["gradleVersion"].asText(), "gradleVersion round-trip")
        assertTrue(meta["customFlag"].asBoolean(), "customFlag round-trip")
        assertEquals(3, meta["retries"].asInt(), "retries round-trip")
        val extra = meta["extra"]
        assertNotNull(extra, "extra nested map must survive")
        assertEquals("ft-db", extra["profile"].asText())
        val tags = extra["tags"]
        assertTrue(tags.isArray && tags.size() == 2, "nested list must survive")
        assertEquals("a", tags[0].asText())
        assertEquals("b", tags[1].asText())
    }

    @Test
    @DisplayName("SYS-027: POST field-override with nested value round-trips under ft-db (H2 jsonb column)")
    fun `SYS-027 POST field override value round-trips`() {
        val summary = firstComponent()
        val id = summary["id"].asText()

        val valuePayload =
            mapOf(
                "strategy" to "GRADLE",
                "flags" to listOf("fast", "parallel"),
                "limits" to mapOf("cpu" to 2, "memoryMb" to 1024),
            )
        val request =
            mapOf(
                "fieldPath" to "buildConfiguration.metadata.sys027",
                "versionRange" to "[1.0,2.0)",
                "value" to valuePayload,
            )

        val createResponseBody =
            mvc
                .perform(
                    post("/rest/api/4/components/$id/field-overrides")
                        .with(editorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString

        val created = objectMapper.readTree(createResponseBody)
        val createdId = created["id"].asText()
        assertFalse(createdId.isNullOrBlank(), "Created override should expose an id")

        val listBody =
            mvc
                .perform(get("/rest/api/4/components/$id/field-overrides").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val list = objectMapper.readTree(listBody)
        assertTrue(list.isArray, "field-overrides list should be an array")
        val match = list.firstOrNull { it["id"].asText() == createdId }
        assertNotNull(match, "Created override must be present in the list")
        val value = match!!["value"]
        assertNotNull(value, "Override value must round-trip from jsonb column")
        assertEquals("GRADLE", value["strategy"].asText())
        val flags = value["flags"]
        assertTrue(flags.isArray && flags.size() == 2, "flags array must round-trip")
        assertEquals("fast", flags[0].asText())
        assertEquals("parallel", flags[1].asText())
        val limits = value["limits"]
        assertNotNull(limits, "nested limits object must survive")
        assertEquals(2, limits["cpu"].asInt())
        assertEquals(1024, limits["memoryMb"].asInt())
    }
}
