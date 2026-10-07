package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.template.ComponentTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

private const val OVERRIDES = "profile-create-test-overrides"

/** Lets a test refuse every profile, standing in for an availability rule stricter than this change's. */
class SwitchableProfileAvailability : ProfileAvailability {
    var refusal: String? = null

    override fun evaluate(profile: ComponentProfile) = ProfileAvailability.Availability(usable = refusal == null, reason = refusal)

    override fun evaluate(template: ComponentTemplate) = ProfileAvailability.Availability(usable = refusal == null, reason = refusal)

    override fun mayOverride(template: ComponentTemplate) = refusal == null
}

@TestConfiguration
class SwitchableProfileAvailabilityConfig {
    @Bean
    @Primary
    fun switchableProfileAvailability() = SwitchableProfileAvailability()
}

/**
 * `POST /rest/api/4/components` with an optional `profile` (design Decisions 7, 8), against the
 * four profiles of the test configuration.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@Import(SwitchableProfileAvailabilityConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
class ProfileOnCreateTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var environment: ConfigurableEnvironment

    @Autowired
    private lateinit var catalog: ComponentProfileCatalog

    @Autowired
    private lateinit var availability: SwitchableProfileAvailability

    @Autowired
    private lateinit var registryConfigRepository: RegistryConfigRepository

    init {
        val testResourcesPath = Paths.get(ProfileOnCreateTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @AfterEach
    fun restore() {
        availability.refusal = null
        seedFieldConfig(emptyMap())
        environment.propertySources.remove(OVERRIDES)
        catalog.reload()
    }

    private fun suffix() = UUID.randomUUID().toString().take(8)

    private fun reloadProfilesWith(vararg values: Pair<String, String>) {
        environment.propertySources.addFirst(
            MapPropertySource(OVERRIDES, values.associate { (key, value) -> "components-registry.component-profiles.$key" to value }),
        )
        catalog.reload()
    }

    private fun seedFieldConfig(value: Map<String, Any?>) {
        val entity = registryConfigRepository.findById("field-config").orElse(RegistryConfigEntity(key = "field-config"))
        entity.value = value
        registryConfigRepository.save(entity)
    }

    private fun create(body: String) =
        mvc.perform(post("/rest/api/4/components").with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content(body))

    /** A regular, non-explicit create: the smallest body the registry accepts. */
    private fun regularBody(
        name: String,
        extra: String = "",
    ) = """{"name":"$name","componentOwner":"owner1"$extra,"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""

    /** An explicit + external create, with what such a component needs beyond the profile check. */
    private fun shippedBody(
        name: String,
        solution: Boolean,
        extra: String = "",
    ) = """{"name":"$name","displayName":"$name","componentOwner":"owner1","solution":$solution,""" +
        """"distributionExplicit":true,"distributionExternal":true,""" +
        """"releaseManager":["rm1"],"securityChampion":["sc1"]$extra,""" +
        """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
        """"mavenArtifacts":[{"groupPattern":"org.example.${name.replace("-", "")}","artifactPattern":"$name"}]}}"""

    private fun assertNotCreated(name: String) {
        mvc.perform(get("/rest/api/4/components/$name").with(adminJwt())).andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("an unknown profile: 400 'profile: ', nothing created")
    fun unknownProfile() {
        val name = "payments-${suffix()}"

        create(regularBody(name, ""","profile":"nightly""""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", startsWith("profile: ")))
        assertNotCreated(name)
    }

    @Test
    @DisplayName("a blank profile is the same as no profile")
    fun blankProfile() {
        create(regularBody("resolution-service-${suffix()}", ""","profile":"  """")).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("the Solution profile with solution: false: 400 'profile: ' naming solution")
    fun classificationDiffers() {
        create(regularBody("payments-solution-${suffix()}", ""","profile":"solution","solution":false"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", allOf(startsWith("profile: "), containsString("solution"))))
    }

    @Test
    @DisplayName("component.solution hidden: the Solution profile is rejected, the create would store a non-solution")
    fun hiddenSolutionFlag() {
        seedFieldConfig(mapOf("component" to mapOf("solution" to mapOf("visibility" to "hidden"))))

        create(shippedBody("payments-solution-${suffix()}", solution = true, extra = ""","profile":"solution""""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", allOf(startsWith("profile: "), containsString("solution"))))
    }

    @Test
    @DisplayName("component.distributionExternal hidden: a profile needing external: true is rejected, naming external")
    fun hiddenExternalFlag() {
        seedFieldConfig(mapOf("component" to mapOf("distributionExternal" to mapOf("visibility" to "hidden"))))

        create(regularBody("payments-${suffix()}", ""","profile":"regular-external","distributionExternal":true"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", allOf(startsWith("profile: "), containsString("external"))))
    }

    @Test
    @DisplayName("a profile the user may not use: 403 with the reason, nothing created")
    fun unusableProfile() {
        availability.refusal = "Refused in this test"
        val name = "payments-${suffix()}"

        create(regularBody(name, ""","profile":"regular-internal""""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorMessage").value("Refused in this test"))
        assertNotCreated(name)
    }

    @Test
    @DisplayName("explicit: ask takes either value")
    fun explicitAsk() {
        create(
            regularBody(
                "payments-${suffix()}",
                ""","profile":"regular-external","distributionExternal":true,"distributionExplicit":false""",
            ),
        ).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("a Portal-shaped create without profile is accepted as today")
    fun noProfile() {
        create(regularBody("payments-${suffix()}", ""","distributionExternal":false""")).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("the Solution rule: payments-dmp-bundle is rejected with the rule's message")
    fun solutionRule() {
        create(shippedBody("payments-dmp-bundle-${suffix()}", solution = true, extra = ""","profile":"solution""""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value("name: A solution key contains -solution, e.g. payments-solution."))
    }

    @Test
    @DisplayName("a regular profile keeps solution words out: resolution-service is rejected")
    fun regularRule() {
        create(regularBody("resolution-service-${suffix()}", ""","profile":"regular-internal""""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", startsWith("name: A regular component's key cannot contain solution or dmp-bundle.")))
    }

    @Test
    @DisplayName("a solution keyed payments-solution is created with the Solution profile")
    fun solutionCreated() {
        create(
            shippedBody("payments-solution-${suffix()}", solution = true, extra = ""","profile":"solution""""),
        ).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("a DMP bundle keyed payments-dmp-bundle is created with the DMP Bundle profile")
    fun dmpBundleCreated() {
        create(
            shippedBody("payments-dmp-bundle-${suffix()}", solution = true, extra = ""","profile":"dmp-bundle""""),
        ).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("surrounding whitespace in the key is trimmed before the rule checks it")
    fun whitespaceTrimmed() {
        create(shippedBody(" payments-solution-${suffix()} ", solution = true, extra = ""","profile":"solution""""))
            .andExpect(status().isCreated)
    }

    @Test
    @DisplayName("an absent field is checked as empty against its rule")
    fun absentValue() {
        reloadProfilesWith(
            "regular-internal.rules.clientCode.pattern" to "^[A-Z]+$",
            "regular-internal.rules.clientCode.message" to "Client code required.",
        )

        create(regularBody("payments-${suffix()}", ""","profile":"regular-internal""""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value("clientCode: Client code required."))
    }

    @Test
    @DisplayName("a [0] rule reads the first VCS entry only")
    fun firstVcsEntryOnly() {
        reloadProfilesWith(
            "regular-internal.rules.baseConfiguration.vcsEntries[0].vcsPath.pattern" to "^ssh://.*$",
            "regular-internal.rules.baseConfiguration.vcsEntries[0].vcsPath.message" to "VCS over ssh.",
        )
        val name = "payments-${suffix()}"
        val body =
            """{"name":"$name","componentOwner":"owner1","profile":"regular-internal",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},"vcsEntries":[""" +
                """{"vcsPath":"ssh://git@git.example.org/pay/first.git","branch":"main","tag":"v1"},""" +
                """{"vcsPath":"https://git.example.org/pay/second.git","branch":"main","tag":"v1","checkoutDirectory":"second"}]}}"""

        create(body).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("after an applied reload that changes a pattern, a create is checked against the new pattern")
    fun rulesFollowReload() {
        reloadProfilesWith("solution.rules.name.pattern" to "^[a-z-]+-solution$")

        create(shippedBody("payments-solution-${suffix()}", solution = true, extra = ""","profile":"solution""""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", startsWith("name: ")))
    }

    @Test
    @DisplayName("a rename of a component created with a profile is checked as today")
    fun renameNotChecked() {
        val created =
            create(regularBody("payments-${suffix()}", ""","profile":"regular-internal""""))
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val detail = objectMapper.readTree(created)

        mvc
            .perform(
                patch("/rest/api/4/components/${detail["id"].asText()}")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${detail["version"].asLong()},"name":"payments-solution-${suffix()}"}"""),
            ).andExpect(status().isOk)
    }
}
