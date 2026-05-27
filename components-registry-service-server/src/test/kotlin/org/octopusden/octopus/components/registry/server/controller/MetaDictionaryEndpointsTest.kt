package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.LabelEntity
import org.octopusden.octopus.components.registry.server.entity.SystemEntity
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.support.viewerJwt
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
import java.nio.file.Paths
import java.util.UUID

/**
 * Dictionary variants of the in-use `/meta/labels` and `/meta/systems` endpoints.
 *
 * Background: the existing `/meta/labels` and `/meta/systems` endpoints expose
 * only codes that are currently attached to at least one component (sourced
 * from the M:N junctions). That is correct for picker UIs that want to filter
 * existing data — but for "only allowed values" pickers (editor multi-select
 * on labels / systems) the Portal needs the full master dictionary, including
 * codes the admin has seeded but no component carries yet.
 *
 * `GET /meta/labels/dictionary` and `GET /meta/systems/dictionary` source
 * directly from the master `labels` / `systems` tables and return the codes
 * sorted ascending. They mirror the read-only shape of the in-use endpoints
 * (`List<String>`, 200 + array regardless of DB state).
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class MetaDictionaryEndpointsTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var labelRepository: LabelRepository

    @Autowired
    private lateinit var systemRepository: SystemRepository

    init {
        val testResourcesPath =
            Paths.get(MetaDictionaryEndpointsTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun unique(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    @Test
    @DisplayName("GET /meta/labels/dictionary returns ALL master labels (including orphans), sorted ascending")
    fun `labels dictionary returns all master rows including orphans sorted`() {
        // Seed the master labels table directly — this endpoint is dictionary-backed,
        // not junction-backed, so a master row that no component carries must still
        // be advertised. That is the whole point of the /dictionary variant.
        val a = unique("dict_a")
        val b = unique("dict_b")
        val g = unique("dict_g")
        listOf(g, a, b).forEach { code ->
            if (labelRepository.findByCode(code) == null) {
                labelRepository.save(LabelEntity(code = code))
            }
        }

        val body =
            mvc
                .perform(get("/rest/api/4/components/meta/labels/dictionary").with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andReturn().response.contentAsString
        val all = objectMapper.readTree(body).map { it.asText() }
        val seeded = all.filter { it == a || it == b || it == g }
        assert(seeded == listOf(a, b, g).sorted()) {
            "expected seeded labels sorted ascending in dictionary; got $seeded"
        }
        assert(all.size == all.toSet().size) { "expected no duplicates; got $all" }
    }

    @Test
    @DisplayName("GET /meta/systems/dictionary returns ALL master systems (including orphans), sorted ascending")
    fun `systems dictionary returns all master rows including orphans sorted`() {
        val a = unique("sd_a")
        val b = unique("sd_b")
        val g = unique("sd_g")
        listOf(g, a, b).forEach { code ->
            if (systemRepository.findByCode(code) == null) {
                systemRepository.save(SystemEntity(code = code))
            }
        }

        val body =
            mvc
                .perform(get("/rest/api/4/components/meta/systems/dictionary").with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andReturn().response.contentAsString
        val all = objectMapper.readTree(body).map { it.asText() }
        val seeded = all.filter { it == a || it == b || it == g }
        assert(seeded == listOf(a, b, g).sorted()) {
            "expected seeded systems sorted ascending in dictionary; got $seeded"
        }
        assert(all.size == all.toSet().size) { "expected no duplicates; got $all" }
    }

    @Test
    @DisplayName("GET /meta/labels/dictionary always returns 200 + JSON array")
    fun `labels dictionary returns 200 array regardless of DB state`() {
        mvc
            .perform(get("/rest/api/4/components/meta/labels/dictionary").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }

    @Test
    @DisplayName("GET /meta/systems/dictionary always returns 200 + JSON array")
    fun `systems dictionary returns 200 array regardless of DB state`() {
        mvc
            .perform(get("/rest/api/4/components/meta/systems/dictionary").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }
}
