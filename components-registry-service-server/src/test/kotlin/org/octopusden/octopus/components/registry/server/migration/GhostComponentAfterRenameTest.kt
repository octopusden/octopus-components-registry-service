package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * SYS-029 — a component renamed via `PATCH /rest/api/4/components/{id}` must not
 * be reachable under its OLD name through ANY CRS read API. Under ft-db the
 * `component_source` row is rewritten atomically, so the OLD name is no longer
 * marked db-sourced; the routing resolver therefore falls back to the git path,
 * whose in-memory `configuration` is still the DSL state captured at startup
 * and happily returns a "ghost" of the pre-rename entity.
 *
 * Reproduced downstream (Releng JIRA-Releng-Plugin build 8.5138) as a 400
 * "The component COMPONENT_FOR_RENAME still exists in CR" — Releng's plugin
 * calls CRS v1 after a v4 rename, sees the ghost, refuses to rename its own DB.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class GhostComponentAfterRenameTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(GhostComponentAfterRenameTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-029: v1 GET must return 404 for a name that was renamed away under ft-db")
    fun v1_getByName_after_rename_must_404() {
        // SUB is a DSL-migrated fixture component; auto-migrate put it into the DB
        // under name=SUB with component_source=(SUB, db).
        val oldName = "SUB"
        val newName = "SUB_RENAMED_SYS029"

        val initial =
            mvc
                .perform(get("/rest/api/4/components/$oldName").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val initialJson = objectMapper.readTree(initial)
        val id = initialJson.path("id").asText()
        val version = initialJson.path("version").asLong()

        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":$version,"name":"$newName"}"""),
            ).andExpect(status().isOk)

        // v4 happy path: new name resolves, old name is gone.
        mvc.perform(get("/rest/api/4/components/$newName").with(adminJwt())).andExpect(status().isOk)

        // The real assertion — v1 must agree.
        // Today this fails: ComponentRoutingResolver falls back to the git resolver
        // for the old name (component_source row gone → default source "git"), and
        // the git resolver's in-memory configuration still has the pre-rename DSL
        // entry, so v1 returns a 200 with the ghost component.
        mvc.perform(get("/rest/api/1/components/$oldName")).andExpect(status().isNotFound)
    }
}
