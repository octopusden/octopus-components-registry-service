package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.service.impl.ConfigValidationException
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.standaloneTemplateProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.cloud.context.refresh.ContextRefresher
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

private const val PREFIX = "components-registry.component-profiles."
private const val OVERRIDES = "reload-test-overrides"

/**
 * `POST /admin/reload-config` reloads the component profiles itself and reports them as
 * `componentProfiles` (design Decision 4). [ContextRefresher] is mocked: a change to
 * service-config is simulated by a highest-precedence property source the profile source reads,
 * which is what a real refresh leaves behind.
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
class ReloadConfigComponentProfilesTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var contextRefresher: ContextRefresher

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var environment: ConfigurableEnvironment

    @Autowired
    private lateinit var catalog: ComponentProfileCatalog

    init {
        val testResourcesPath =
            Paths.get(ReloadConfigComponentProfilesTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @AfterEach
    fun restore() {
        environment.propertySources.remove(OVERRIDES)
        reset(contextRefresher)
        catalog.reload()
    }

    private fun changeServiceConfig(vararg values: Pair<String, String>) {
        environment.propertySources.remove(OVERRIDES)
        environment.propertySources.addFirst(MapPropertySource(OVERRIDES, values.associate { (key, value) -> PREFIX + key to value }))
    }

    private fun reload() = mvc.perform(post("/rest/api/4/admin/reload-config").with(adminJwt()))

    private fun solutionTitle() = catalog.profiles().single { it.id == "solution" }.title

    @Test
    @DisplayName("a valid change: 200 with componentProfiles applied, status and changedKeys kept, new title in use")
    fun validChange() {
        changeServiceConfig("solution.title" to "Solution (renamed)")

        reload()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("reloaded"))
            .andExpect(jsonPath("$.changedKeys").isArray)
            .andExpect(jsonPath("$.componentProfiles.status").value("applied"))
            .andExpect(jsonPath("$.componentProfiles.entries.length()").value(4))

        assertEquals("Solution (renamed)", solutionTitle())
    }

    @Test
    @DisplayName("one title changed and another regular profile broken: 422 component-profiles, previous title kept")
    fun invalidRegularProfileKeepsPrevious() {
        val before = solutionTitle()
        changeServiceConfig("solution.title" to "Solution (renamed)", "dmp-bundle.order" to "ten")

        reload()
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error").value("component-profiles"))
            .andExpect(jsonPath("$.componentProfiles.status").value("failed"))
            .andExpect(jsonPath("$.componentProfiles.entries[?(@.id == 'dmp-bundle')].status").value("failed"))
            .andExpect(
                jsonPath(
                    "$.componentProfiles.entries[?(@.id == 'dmp-bundle')].problems[0]",
                ).value("dmp-bundle.order: 'ten' is not a whole number"),
            )

        assertEquals(before, solutionTitle())
    }

    @Test
    @DisplayName("a valid and a broken template added: 200 applied, the valid one live, the broken one failed with its problems")
    fun templateEntries() {
        changeServiceConfig(*standaloneTemplateProperties().toList().toTypedArray(), "broken-plugin.kind" to "template")

        reload()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.componentProfiles.status").value("applied"))
            .andExpect(jsonPath("$.componentProfiles.entries[?(@.id == 'client-plugin')].status").value("live"))
            .andExpect(jsonPath("$.componentProfiles.entries[?(@.id == 'broken-plugin')].status").value("failed"))
            .andExpect(
                jsonPath(
                    "$.componentProfiles.entries[?(@.id == 'broken-plugin')].problems[0]",
                ).value("broken-plugin.title: required"),
            )

        assertEquals(listOf("client-plugin"), catalog.templates().map { it.id })
    }

    @Test
    @DisplayName("Decision 4: a template that leaves its VCS tag to component-defaults is live after a reload")
    fun templateUsesComponentDefaults() {
        changeServiceConfig(*standaloneTemplateProperties().filterKeys { !it.endsWith("vcsEntries[0].tag") }.toList().toTypedArray())

        reload().andExpect(jsonPath("$.componentProfiles.entries[?(@.id == 'client-plugin')].status").value("live"))
    }

    @Test
    @DisplayName("a corrected configuration reloaded after a failure is applied")
    fun fixedAfterFailure() {
        changeServiceConfig("dmp-bundle.order" to "ten")
        reload().andExpect(status().isUnprocessableEntity)
        changeServiceConfig("dmp-bundle.order" to "45")

        reload()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.componentProfiles.status").value("applied"))

        assertEquals(45, catalog.profiles().single { it.id == "dmp-bundle" }.order)
    }

    @Test
    @DisplayName("field-config fails the refresh: 422 config-validation, and the profiles are still reloaded and reported")
    fun fieldConfigFailureStillReloadsProfiles() {
        doThrow(ConfigValidationException("field-config.component.displayName.visibility: 'sometimes' is not allowed"))
            .`when`(contextRefresher)
            .refresh()
        changeServiceConfig("solution.title" to "Solution (renamed)")

        reload()
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error").value("config-validation"))
            .andExpect(jsonPath("$.componentProfiles.status").value("applied"))

        assertEquals("Solution (renamed)", solutionTitle())
    }

    @Test
    @DisplayName("any other refresh failure (a field-config binding error): 500 config-refresh, the profiles still reloaded and reported")
    fun otherRefreshFailureStillReportsProfiles() {
        doThrow(IllegalStateException("Could not bind properties to 'AdminConfigProperties': required 'maybe' is not a Boolean"))
            .`when`(contextRefresher)
            .refresh()
        changeServiceConfig("solution.title" to "Solution (renamed)")

        reload()
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error").value("config-refresh"))
            .andExpect(
                jsonPath("$.message").value("Could not bind properties to 'AdminConfigProperties': required 'maybe' is not a Boolean"),
            ).andExpect(jsonPath("$.componentProfiles.status").value("applied"))

        assertEquals("Solution (renamed)", solutionTitle())
    }
}
