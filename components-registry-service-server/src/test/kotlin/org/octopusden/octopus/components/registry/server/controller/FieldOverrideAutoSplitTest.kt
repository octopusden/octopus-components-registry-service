package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Paths
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * ADR-018 refinement (b): write-time auto-split. When a per-attribute field override introduces a
 * boundary INSIDE a covering `RANGE_PRESENCE` range, the covering presence row must be split at the
 * override's interior edges so range-view enumeration (one config per presence row) keeps constant
 * resolved values within each range. Coverage (the union) is unchanged — only the breakpoints move.
 *
 * Integration test (ft-db = H2 + auto-migrate): an API-created component gets a RANGE_PRESENCE row
 * injected to mimic a migrated (bounded-coverage) component, then a real field-override POST drives
 * the auto-split, asserted on the persisted presence rows.
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
class FieldOverrideAutoSplitTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

    init {
        val testResourcesPath =
            Paths.get(FieldOverrideAutoSplitTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("ADR-018(b): override [2.0,3.0) inside presence [1.0,10.0) splits coverage into three rows")
    fun `interior override auto-splits the covering presence row`() {
        val id = createComponent("autosplit_${UUID.randomUUID().toString().take(8)}")
        seedRangePresence(id, "[1.0,10.0)")

        createFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[2.0,3.0)","value":"11"}""",
        )

        val presenceRanges = rangePresenceRanges(id)
        assertEquals(
            listOf("[1.0,2.0)", "[2.0,3.0)", "[3.0,10.0)"),
            presenceRanges,
            "the covering [1.0,10.0) presence row must split at the override's interior edges 2.0 and 3.0",
        )
    }

    @Test
    @DisplayName("ADR-018(b): open-upper override [2.0,) inside presence [1.0,) splits into [1.0,2.0),[2.0,)")
    fun `open upper override auto-splits at its floor`() {
        val id = createComponent("autosplitopen_${UUID.randomUUID().toString().take(8)}")
        seedRangePresence(id, "[1.0,)")

        createFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[2.0,)","value":"21"}""",
        )

        assertEquals(
            listOf("[1.0,2.0)", "[2.0,)"),
            rangePresenceRanges(id),
            "open-upper override floor 2.0 must split the open-upper presence row",
        )
    }

    @Test
    @DisplayName("ADR-018(b): override equal to the covering presence range leaves coverage unchanged")
    fun `override equal to presence is a no-op for coverage`() {
        val id = createComponent("autosplitnoop_${UUID.randomUUID().toString().take(8)}")
        seedRangePresence(id, "[1.0,2.0)")

        createFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0,2.0)","value":"11"}""",
        )

        assertEquals(
            listOf("[1.0,2.0)"),
            rangePresenceRanges(id),
            "an override equal to the presence range introduces no interior edge → no split",
        )
    }

    @Test
    @DisplayName("V2 (ADR-018 §6): a second open-upper override on the same attribute is rejected (400)")
    fun `two open upper overrides on same attribute are rejected`() {
        val id = createComponent("v2_${UUID.randomUUID().toString().take(8)}")
        createFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0,)","value":"11"}""",
        )
        // Second open-upper on the SAME attribute → always overlaps → 400 with the V2 message.
        val response =
            mvc
                .perform(
                    post("/rest/api/4/components/$id/field-overrides")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"overriddenAttribute":"build.javaVersion","versionRange":"[2.0,)","value":"17"}"""),
                ).andExpect(status().isBadRequest)
                .andReturn()
                .response.contentAsString
        assertEquals(true, response.contains("open-upper"), "rejection message should explain the open-upper conflict; got: $response")
    }

    @Test
    @DisplayName("V4 (ADR-018 §6): open-upper overrides on DIFFERENT attributes are both allowed")
    fun `open upper overrides on different attributes are allowed`() {
        val id = createComponent("v4_${UUID.randomUUID().toString().take(8)}")
        createFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0,)","value":"11"}""",
        )
        // Different attribute → independent → allowed (no V2/V3 conflict across attributes).
        createFieldOverride(
            id,
            """{"overriddenAttribute":"build.mavenVersion","versionRange":"[1.0,)","value":"3.9.0"}""",
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun createComponent(name: String): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name",""" +
                                """"componentOwner":"owner1",""" +
                                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                        ),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    /** Insert a RANGE_PRESENCE coverage row directly, mimicking a migrated bounded-coverage component. */
    private fun seedRangePresence(
        componentId: String,
        range: String,
    ) {
        val component = componentRepository.findById(UUID.fromString(componentId)).orElseThrow()
        configurationRepository.save(
            ComponentConfigurationEntity(
                component = component,
                versionRange = range,
                overriddenAttribute = null,
                rowType = "RANGE_PRESENCE",
            ),
        )
    }

    private fun createFieldOverride(
        componentId: String,
        payload: String,
    ) {
        mvc
            .perform(
                post("/rest/api/4/components/$componentId/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().is2xxSuccessful)
    }

    private fun rangePresenceRanges(componentId: String): List<String> =
        configurationRepository
            .findByComponentId(UUID.fromString(componentId))
            .filter { it.rowType == "RANGE_PRESENCE" }
            .map { it.versionRange }
            .sortedWith { a, b -> compareRangeFloors(a, b) }

    /** Order presence ranges by their lower bound so the assertion is deterministic. */
    private fun compareRangeFloors(
        a: String,
        b: String,
    ): Int {
        fun floor(r: String): org.apache.maven.artifact.versioning.DefaultArtifactVersion {
            val lo = r.trim().removePrefix("[").removePrefix("(").substringBefore(",").trim()
            return org.apache.maven.artifact.versioning.DefaultArtifactVersion(lo.ifEmpty { "0" })
        }
        return floor(a).compareTo(floor(b))
    }
}
