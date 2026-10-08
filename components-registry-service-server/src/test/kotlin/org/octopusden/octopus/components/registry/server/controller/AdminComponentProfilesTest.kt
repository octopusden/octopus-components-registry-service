package org.octopusden.octopus.components.registry.server.controller

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.octopusden.octopus.components.registry.server.support.standaloneTemplateProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

private const val PREFIX = "components-registry.component-profiles."
private const val OVERRIDES = "admin-read-test-overrides"

/** `GET /rest/api/4/admin/component-profiles` (Decision 11) against the test configuration's four profiles. */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
class AdminComponentProfilesTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var environment: ConfigurableEnvironment

    @Autowired
    private lateinit var catalog: ComponentProfileCatalog

    init {
        val testResourcesPath =
            Paths.get(AdminComponentProfilesTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @AfterEach
    fun restore() {
        environment.propertySources.remove(OVERRIDES)
        catalog.reload()
    }

    private fun reloadWith(values: Map<String, String>) {
        environment.propertySources.addFirst(MapPropertySource(OVERRIDES, values))
        catalog.reload()
    }

    private fun read(jwt: RequestPostProcessor = adminJwt()) = mvc.perform(get("/rest/api/4/admin/component-profiles").with(jwt))

    @Test
    @DisplayName("Decision 11: live and failed entries come with their status, problems, definition and configuration text")
    fun liveAndFailed() {
        reloadWith(
            standaloneTemplateProperties("client-plugin").mapKeys { PREFIX + it.key } +
                mapOf("${PREFIX}broken.kind" to "template", "${PREFIX}broken.maintainer" to "jdoe"),
        )

        read()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lastLoad.status").value("applied"))
            .andExpect(jsonPath("$.entries[?(@.id == 'client-plugin')].status").value("live"))
            .andExpect(jsonPath("$.entries[?(@.id == 'client-plugin')].template.version").value(3))
            .andExpect(jsonPath("$.entries[?(@.id == 'client-plugin')].configuration").value(hasItem(containsString("kind: template"))))
            .andExpect(jsonPath("$.entries[?(@.id == 'broken')].status").value("failed"))
            .andExpect(jsonPath("$.entries[?(@.id == 'broken')].problems[*]").value(hasItem("broken.maintainer: unknown key")))
            .andExpect(jsonPath("$.entries[?(@.id == 'broken')].configuration").value(hasItem(containsString("maintainer: jdoe"))))
            .andExpect(jsonPath("$.entries[?(@.id == 'solution')].profile.kind").value("regular"))
    }

    @Test
    @DisplayName("Decision 11: the configuration version is the one the config server reported, absent otherwise")
    fun configVersion() {
        read().andExpect(jsonPath("$.configVersion").value(nullValue()))

        reloadWith(mapOf("config.client.version" to "4f2c1a9"))

        read().andExpect(jsonPath("$.configVersion").value("4f2c1a9"))
    }

    @Test
    @DisplayName("Decision 11: a failed reload is said so, with its problems, and the entries still in use are listed")
    fun lastReloadFailed() {
        reloadWith(mapOf("${PREFIX}solution.order" to "first"))

        read()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lastLoad.status").value("failed"))
            .andExpect(jsonPath("$.lastLoad.entries[?(@.id == 'solution')].problems[*]").value(hasItem(containsString("solution.order"))))
            .andExpect(jsonPath("$.entries[?(@.id == 'solution')].status").value("live"))
    }

    @Test
    @DisplayName("a user without IMPORT_DATA is refused")
    fun refused() {
        read(editorJwt()).andExpect(status().isForbidden)
    }
}
