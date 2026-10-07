package org.octopusden.octopus.components.registry.server.controller

import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import org.octopusden.octopus.components.registry.server.template.standaloneTemplateProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

private const val OVERRIDES = "listing-test-overrides"
private const val SOLUTION_NAME_PATTERN = "^[a-z][a-z0-9-]*-solution(-[a-z0-9-]+)?$"

/** `GET /rest/api/4/component-profiles` against the four profiles of the test configuration. */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
class ComponentProfileControllerV4Test {
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
            Paths.get(ComponentProfileControllerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @AfterEach
    fun restore() {
        environment.propertySources.remove(OVERRIDES)
        catalog.reload()
    }

    private fun reloadWith(vararg values: Pair<String, String>) {
        environment.propertySources.addFirst(
            MapPropertySource(OVERRIDES, values.associate { (key, value) -> "components-registry.component-profiles.$key" to value }),
        )
        catalog.reload()
    }

    private fun list(jwt: RequestPostProcessor = adminJwt()) = mvc.perform(get("/rest/api/4/component-profiles").with(jwt))

    @Test
    @DisplayName("profiles are listed in order with id, kind, title, description and classification")
    fun listedInOrder() {
        list()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profiles[*].id", contains("regular-external", "regular-internal", "solution", "dmp-bundle")))
            .andExpect(jsonPath("$.profiles[0].kind").value("regular"))
            .andExpect(jsonPath("$.profiles[0].title").value("Regular external component"))
            .andExpect(jsonPath("$.profiles[0].description").isNotEmpty)
            .andExpect(jsonPath("$.profiles[0].classification.external").value(true))
            .andExpect(jsonPath("$.profiles[0].classification.explicit").value("ask"))
            .andExpect(jsonPath("$.profiles[0].classification.solution").value(false))
            .andExpect(jsonPath("$.profiles[2].classification.explicit").value("true"))
            .andExpect(jsonPath("$.profiles[2].classification.solution").value(true))
    }

    @Test
    @DisplayName("each profile carries its field rules with path, pattern and message")
    fun rulesReturned() {
        list()
            .andExpect(jsonPath("$.profiles[2].rules[0].path").value("name"))
            .andExpect(jsonPath("$.profiles[2].rules[0].pattern").value(SOLUTION_NAME_PATTERN))
            .andExpect(jsonPath("$.profiles[2].rules[0].message").value("A solution key contains -solution, e.g. payments-solution."))
    }

    @Test
    @DisplayName("a profile without rules carries an empty rule list")
    fun emptyRules() {
        reloadWith(
            "plain.kind" to "regular",
            "plain.title" to "Plain",
            "plain.description" to "No rules",
            "plain.order" to "50",
            "plain.classification.external" to "false",
            "plain.classification.explicit" to "false",
        )

        list().andExpect(jsonPath("$.profiles[4].id").value("plain")).andExpect(jsonPath("$.profiles[4].rules").isEmpty)
    }

    @Test
    @DisplayName("profiles with the same order are listed by id")
    fun orderTies() {
        reloadWith("solution.order" to "10")

        list().andExpect(jsonPath("$.profiles[0].id").value("regular-external")).andExpect(jsonPath("$.profiles[1].id").value("solution"))
    }

    @Test
    @DisplayName("a failed template entry is not listed")
    fun failedEntryNotListed() {
        reloadWith("client-plugin.kind" to "template")

        list().andExpect(jsonPath("$.profiles.length()").value(4))
    }

    @Test
    @DisplayName(
        "Decision 1: a live template is listed next to the profiles by order then id, with kind, version, classification and rules",
    )
    fun templateListed() {
        reloadWith(
            *standaloneTemplateProperties().toList().toTypedArray(),
            "client-plugin.order" to "25",
            "client-plugin.rules.name.pattern" to "^[a-z]+-plugin-[a-z0-9]+$",
            "client-plugin.rules.name.message" to "A plugin key is <client>-plugin-<code>.",
        )

        list()
            .andExpect(
                jsonPath("$.profiles[*].id", contains("regular-external", "regular-internal", "client-plugin", "solution", "dmp-bundle")),
            ).andExpect(jsonPath("$.profiles[2].kind").value("template"))
            .andExpect(jsonPath("$.profiles[2].version").value(3))
            .andExpect(jsonPath("$.profiles[2].classification.explicit").value("false"))
            .andExpect(jsonPath("$.profiles[2].classification.external").value(true))
            .andExpect(jsonPath("$.profiles[2].rules[0].path").value("name"))
            .andExpect(jsonPath("$.profiles[2].usable").value(true))
            .andExpect(jsonPath("$.profiles[0].version").doesNotExist())
    }

    @Test
    @DisplayName("a user who may create components may use every profile")
    fun usableForCreator() {
        list()
            .andExpect(jsonPath("$.profiles[*].usable", contains(true, true, true, true)))
            .andExpect(jsonPath("$.profiles[0].unusableReason").doesNotExist())
    }

    @Test
    @DisplayName("a user who may not create components may use none, with the reason")
    fun unusableForViewer() {
        list(viewerJwt())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profiles[*].usable", contains(false, false, false, false)))
            .andExpect(jsonPath("$.profiles[0].unusableReason").value("You do not have permission to create components"))
    }

    @Test
    @DisplayName("a caller without ACCESS_COMPONENTS is refused")
    fun refusedWithoutAccess() {
        list(jwt().jwt { it.claim("preferred_username", "nobody") }).andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("after an applied reload that changes a rule pattern, the listing returns the new pattern")
    fun rulesFollowReload() {
        reloadWith("solution.rules.name.pattern" to "^[a-z-]+-solution$")

        list().andExpect(jsonPath("$.profiles[2].rules[0].pattern").value("^[a-z-]+-solution$"))
    }
}
