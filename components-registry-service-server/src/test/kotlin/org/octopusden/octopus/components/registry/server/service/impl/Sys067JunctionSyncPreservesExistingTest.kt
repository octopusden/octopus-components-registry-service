package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSystemRepository
import org.octopusden.octopus.components.registry.server.support.adminJwt
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
import java.util.UUID

/**
 * SYS-067 — editing a component's `labels` / `systems` / `build.requiredTools` makes the stored rows
 * match the submitted list exactly: entries kept in the list stay, new entries are added, entries
 * left out are removed, re-sending the same list is a no-op, and an empty list clears the membership.
 *
 * The three junction sync methods share one delete-all-then-reinsert-vs-set-diff code path (labels was
 * the path observed in the production incident; systems and required-tools were latent). keep+add is
 * exercised on all three; the remove / no-op / clear halves of the replace contract are exercised on
 * labels (the shared code path makes them representative). Every assertion reads persisted junction
 * rows via the repository, never the in-memory response projection, so a masked loss cannot pass.
 *
 * Baselines use unique per-run codes so a pre-fix failure lands on the behaviour under test, not on
 * baseline setup, regardless of the seed component's state.
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
class Sys067JunctionSyncPreservesExistingTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var componentLabelRepository: ComponentLabelRepository

    @Autowired
    private lateinit var componentSystemRepository: ComponentSystemRepository

    @Autowired
    private lateinit var componentConfigurationRepository: ComponentConfigurationRepository

    @Autowired
    private lateinit var componentRequiredToolRepository: ComponentRequiredToolRepository

    init {
        val testResourcesPath =
            Paths
                .get(Sys067JunctionSyncPreservesExistingTest::class.java.getResource("/expected-data")!!.toURI())
                .parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun firstComponentId(): String {
        val body =
            mvc
                .perform(get("/rest/api/4/components").with(editorJwt()).param("page", "0").param("size", "1"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).path("content")[0]["id"].asText()
    }

    private fun currentVersion(id: String): Long {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$id").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["version"].asLong()
    }

    // admin: the ft-db seed component has no owner/RM/SC, so a plain editor is 403'd by the
    // per-component edit gate. These tests pin junction persistence, not authorization.
    private fun patch(
        id: String,
        payload: Map<String, Any?>,
    ) {
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(payload + ("version" to currentVersion(id)))),
            ).andExpect(status().is2xxSuccessful)
    }

    private fun labelsOf(id: String): Set<String> =
        componentLabelRepository.findByComponentId(UUID.fromString(id)).map { it.labelCode }.toSet()

    private fun systemsOf(id: String): Set<String> =
        componentSystemRepository.findByComponentId(UUID.fromString(id)).map { it.systemCode }.toSet()

    private fun baseConfigId(id: String): UUID =
        componentConfigurationRepository
            .findByComponentId(UUID.fromString(id))
            .first { it.rowType == "BASE" }
            .id!!

    private fun requiredToolsOf(id: String): Set<String> =
        componentRequiredToolRepository.findByComponentConfigurationId(baseConfigId(id)).map { it.toolName }.toSet()

    // -- keep + add across all three junction types (the incident shape) --

    @Test
    @DisplayName("SYS-067: adding a label preserves the existing labels")
    fun `SYS-067 adding a label preserves the existing labels`() {
        val id = firstComponentId()
        val keep = "keep-${UUID.randomUUID()}"
        val add = "add-${UUID.randomUUID()}"
        patch(id, mapOf("labels" to listOf(keep)))
        patch(id, mapOf("labels" to listOf(keep, add)))
        assertEquals(setOf(keep, add), labelsOf(id), "component_labels must hold both labels after add")
    }

    @Test
    @DisplayName("SYS-067: adding a system preserves the existing systems")
    fun `SYS-067 adding a system preserves the existing systems`() {
        val id = firstComponentId()
        // Codes must be in the ft-db supportedSystems allowlist (NONE,CLASSIC,ALFA).
        patch(id, mapOf("systems" to listOf("CLASSIC")))
        patch(id, mapOf("systems" to listOf("CLASSIC", "ALFA")))
        assertEquals(setOf("CLASSIC", "ALFA"), systemsOf(id), "component_systems must hold both systems after add")
    }

    @Test
    @DisplayName("SYS-067: adding a required tool preserves the existing required tools")
    fun `SYS-067 adding a required tool preserves the existing required tools`() {
        val id = firstComponentId()
        val keep = "keep-${UUID.randomUUID()}"
        val add = "add-${UUID.randomUUID()}"
        patch(id, mapOf("baseConfiguration" to mapOf("requiredTools" to listOf(keep))))
        patch(id, mapOf("baseConfiguration" to mapOf("requiredTools" to listOf(keep, add))))
        assertEquals(setOf(keep, add), requiredToolsOf(id), "component_required_tools must hold both tools after add")
    }

    // -- the rest of the replace contract, on labels (shared code path) --

    @Test
    @DisplayName("SYS-067: a mixed edit keeps the intersection, adds the new and drops the removed")
    fun `SYS-067 a mixed edit keeps the intersection, adds the new and drops the removed`() {
        val id = firstComponentId()
        val a = "a-${UUID.randomUUID()}"
        val b = "b-${UUID.randomUUID()}"
        val c = "c-${UUID.randomUUID()}"
        patch(id, mapOf("labels" to listOf(a, b)))
        patch(id, mapOf("labels" to listOf(b, c)))
        assertEquals(setOf(b, c), labelsOf(id), "[a,b] -> [b,c]: b kept, a removed, c added")
    }

    @Test
    @DisplayName("SYS-067: re-submitting the same list is a no-op")
    fun `SYS-067 re-submitting the same list is a no-op`() {
        val id = firstComponentId()
        val a = "a-${UUID.randomUUID()}"
        val b = "b-${UUID.randomUUID()}"
        patch(id, mapOf("labels" to listOf(a, b)))
        patch(id, mapOf("labels" to listOf(a, b)))
        assertEquals(setOf(a, b), labelsOf(id), "re-sending the identical list leaves the membership unchanged")
    }

    @Test
    @DisplayName("SYS-067: an empty list clears the membership")
    fun `SYS-067 an empty list clears the membership`() {
        val id = firstComponentId()
        val a = "a-${UUID.randomUUID()}"
        val b = "b-${UUID.randomUUID()}"
        patch(id, mapOf("labels" to listOf(a, b)))
        patch(id, mapOf("labels" to emptyList<String>()))
        assertEquals(emptySet<String>(), labelsOf(id), "an empty labels list removes all rows")
    }
}
