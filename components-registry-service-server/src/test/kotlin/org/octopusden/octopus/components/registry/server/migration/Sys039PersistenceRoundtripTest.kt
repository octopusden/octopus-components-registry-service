package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * SYS-039 — round-trip the six new component-detail scalar/array fields
 * through the full HTTP → service → Hibernate → H2(jsonb-compat) →
 * Hibernate → service → HTTP path. The mapper unit suite covers the
 * Entity↔DTO projection in isolation; this test pins the column types
 * (TEXT, BOOLEAN, text[]) round-trip correctly under the ft-db profile
 * the rest of the integration suite uses.
 *
 * Two assertions:
 *   1. PATCH all six fields → re-fetch returns the patched values.
 *   2. `releasesInDefaultBranch=false` round-trips as JSON `false`, not
 *      `null` (the field is a nullable `Boolean?` column; null and false
 *      are semantically distinct and must serialize distinctly).
 *
 * The test piggy-backs on the existing ft-db component fixtures used by
 * `FtDbProfileWriteTest`; no new test data is required.
 *
 * Write-side dedup of `labels` (B6) is verified separately on its own
 * PR — at the read layer the `Array → Set` mapper conversion masks
 * dupes either way, so a meaningful write-side test would have to
 * bypass the mapper (read entity via repo) and is left as a follow-up.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class Sys039PersistenceRoundtripTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths
                .get(Sys039PersistenceRoundtripTest::class.java.getResource("/expected-data")!!.toURI())
                .parent
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
        assertTrue(content.isArray && content.size() > 0)
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
    @DisplayName("SYS-039: all six new scalar/array fields round-trip via PATCH + GET")
    fun allSixFields_roundtrip() {
        val summary = firstComponent()
        val id = summary["id"].asText()
        val detail = getComponent(id)
        val version = detail["version"].asLong()

        val payload =
            mapOf(
                "version" to version,
                "groupId" to "org.example.alpha",
                "releaseManager" to "rm-user",
                "securityChampion" to "sc-user",
                "copyright" to "(c) 2026 Acme Inc.",
                "releasesInDefaultBranch" to true,
                "labels" to listOf("backend", "internal"),
            )

        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(payload)),
            ).andExpect(status().is2xxSuccessful)

        val updated = getComponent(id)
        assertEquals("org.example.alpha", updated["groupId"].asText())
        assertEquals("rm-user", updated["releaseManager"].asText())
        assertEquals("sc-user", updated["securityChampion"].asText())
        assertEquals("(c) 2026 Acme Inc.", updated["copyright"].asText())
        assertTrue(updated["releasesInDefaultBranch"].asBoolean(), "Boolean true round-trip")

        val labels = updated["labels"]
        assertNotNull(labels)
        assertTrue(labels.isArray)
        val labelSet = labels.map { it.asText() }.toSet()
        assertEquals(setOf("backend", "internal"), labelSet, "text[] round-trip")
    }

    @Test
    @DisplayName("SYS-039: releasesInDefaultBranch=false stays distinct from null after round-trip")
    fun releasesInDefaultBranch_falseDistinctFromNull() {
        val summary = firstComponent()
        val id = summary["id"].asText()
        var detail = getComponent(id)
        var version = detail["version"].asLong()

        // Establish a non-false baseline: PATCH to true first so the
        // subsequent false-write is observable as an actual change.
        // Without this step, a stored null or already-false baseline
        // would let the test pass even if the false-write path were
        // broken (the column would just stay at its prior value).
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            mapOf("version" to version, "releasesInDefaultBranch" to true),
                        ),
                    ),
            ).andExpect(status().is2xxSuccessful)
        detail = getComponent(id)
        assertEquals(true, detail["releasesInDefaultBranch"].asBoolean(false), "baseline true established")
        version = detail["version"].asLong()

        // Now the actual test — patch from true to false.
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            mapOf("version" to version, "releasesInDefaultBranch" to false),
                        ),
                    ),
            ).andExpect(status().is2xxSuccessful)

        val updated = getComponent(id)
        val node = updated["releasesInDefaultBranch"]
        assertNotNull(node, "nullable Boolean column must serialize false, not omit")
        assertTrue(!node.isNull, "false must round-trip as JSON false, not null")
        assertEquals(false, node.asBoolean(true), "explicit false survives round-trip from true→false")
    }
}
