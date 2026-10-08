package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.LabelEntity
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.model.ComponentTemplate
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.components.registry.server.security.PermissionEvaluator
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability
import org.octopusden.octopus.components.registry.server.service.impl.ActiveStatus
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService
import org.octopusden.octopus.components.registry.server.service.impl.PermissionProfileAvailability
import org.octopusden.octopus.components.registry.server.support.TEMPLATE_ID
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.at
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.octopusden.octopus.components.registry.server.support.flatten
import org.octopusden.octopus.components.registry.server.support.standaloneTemplate
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

private const val OVERRIDES = "template-test-overrides"
private const val BASE = "/rest/api/4/component-templates"

/**
 * The template endpoints against the design's example template (fixing what it would otherwise
 * take from `component-defaults`), with the real create, a stubbed employee service, and an
 * availability rule whose override answer a test can switch off.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class, ComponentTemplateControllerV4Test.Availability::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
class ComponentTemplateControllerV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var employees: EmployeeDirectoryService

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var environment: ConfigurableEnvironment

    @Autowired
    private lateinit var catalog: ComponentProfileCatalog

    @Autowired
    private lateinit var switchable: SwitchableAvailability

    @Autowired
    private lateinit var labelRepository: LabelRepository

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var componentSourceRepository: ComponentSourceRepository

    @Autowired
    private lateinit var registryConfigRepository: RegistryConfigRepository

    /** The real permission rule, with the override answer switchable for the one test that needs it. */
    class SwitchableAvailability(
        private val real: ProfileAvailability,
    ) : ProfileAvailability by real {
        var overrideAllowed = true

        override fun mayOverride(template: ComponentTemplate): Boolean = overrideAllowed && real.mayOverride(template)
    }

    @TestConfiguration
    class Availability {
        @Bean
        @Primary
        fun switchableAvailability(permissionEvaluator: PermissionEvaluator) =
            SwitchableAvailability(PermissionProfileAvailability(permissionEvaluator::hasPermission))
    }

    init {
        val testResourcesPath =
            Paths.get(ComponentTemplateControllerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeEach
    fun setUp() {
        `when`(employees.isActive(anyString())).thenReturn(ActiveStatus.ACTIVE)
        `when`(employees.isEnabled()).thenReturn(true)
        if (labelRepository.findByCode("plugin") == null) labelRepository.save(LabelEntity(code = "plugin"))
        configure(standaloneTemplate())
    }

    @AfterEach
    fun restore() {
        switchable.overrideAllowed = true
        environment.propertySources.remove(OVERRIDES)
        catalog.reload()
    }

    private fun configure(
        template: Map<String, Any>,
        id: String = TEMPLATE_ID,
    ) {
        environment.propertySources.remove(OVERRIDES)
        environment.propertySources.addFirst(
            MapPropertySource(OVERRIDES, flatten(template, id).mapKeys { "components-registry.component-profiles.${it.key}" }),
        )
        catalog.reload()
    }

    private fun pluginCode() =
        "P" + UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(8)
            .uppercase()

    private fun body(
        pluginCode: String = pluginCode(),
        extra: Map<String, Any?> = emptyMap(),
        parameters: Map<String, Any> = emptyMap(),
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "parameters" to
                    mapOf(
                        "CLIENT_CODE" to listOf("ACME"),
                        "PLUGIN_CODE" to listOf(pluginCode),
                        "PLUGIN_NAME" to listOf("Plugin $pluginCode"),
                    ) + parameters,
            ) + extra,
        )

    private fun describe(
        id: String = TEMPLATE_ID,
        jwt: RequestPostProcessor = adminJwt(),
    ) = mvc.perform(get("$BASE/$id").with(jwt))

    private fun components(
        body: String,
        dryRun: Boolean? = null,
        id: String = TEMPLATE_ID,
        jwt: RequestPostProcessor = adminJwt(),
    ): ResultActions {
        val request = post("$BASE/$id/components").with(jwt).contentType(MediaType.APPLICATION_JSON).content(body)
        return mvc.perform(if (dryRun == null) request else request.param("dryRun", dryRun.toString()))
    }

    private fun counts() =
        listOf(componentRepository.count(), auditLogRepository.count(), labelRepository.count(), componentSourceRepository.count())

    private fun withFieldConfig(
        value: Map<String, Any?>,
        block: () -> Unit,
    ) {
        fun seed(config: Map<String, Any?>) {
            val entity = registryConfigRepository.findById("field-config").orElse(RegistryConfigEntity(key = "field-config"))
            entity.value = config
            registryConfigRepository.save(entity)
        }
        seed(value)
        try {
            block()
        } finally {
            seed(emptyMap())
        }
    }

    @Test
    @DisplayName("describe: a text parameter's pattern, message and maximum length come with its default")
    fun describeText() {
        configure(
            standaloneTemplate().apply {
                at("parameters.PLUGIN_NAME").putAll(mapOf("max-length" to "40", "default" to "Core", "hint" to "Shown to clients"))
            },
        )

        describe()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(TEMPLATE_ID))
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.classification.explicit").value("false"))
            .andExpect(jsonPath("$.overridable", contains("baseConfiguration.vcsEntries[0].vcsPath", "baseConfiguration.jira.projectKey")))
            .andExpect(jsonPath("$.parameters[*].name", contains("CLIENT_CODE", "PLUGIN_CODE", "PLUGIN_NAME", "COMPONENT_OWNER")))
            .andExpect(jsonPath("$.parameters[1].type").value("text"))
            .andExpect(jsonPath("$.parameters[1].pattern").value("^[A-Z][A-Z0-9]{2,15}$"))
            .andExpect(jsonPath("$.parameters[1].message").isNotEmpty)
            .andExpect(jsonPath("$.parameters[2].maxLength").value(40))
            .andExpect(jsonPath("$.parameters[2].hint").value("Shown to clients"))
            .andExpect(jsonPath("$.parameters[2].default", contains("Core")))
    }

    @Test
    @DisplayName("describe: a select returns its options; a person defaulting to current-user returns the caller")
    fun describeSelectAndPerson() {
        describe(jwt = viewerJwt("jdoe"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Client plugin"))
            .andExpect(jsonPath("$.description").value("A plugin built for one client."))
            .andExpect(jsonPath("$.parameters[0].type").value("select"))
            .andExpect(jsonPath("$.parameters[0].options", contains("ACME", "GLOBEX")))
            .andExpect(jsonPath("$.parameters[0].multiple").value(false))
            .andExpect(jsonPath("$.parameters[3].type").value("person"))
            .andExpect(jsonPath("$.parameters[3].multiple").value(false))
            .andExpect(jsonPath("$.parameters[3].default", contains("jdoe")))
    }

    @Test
    @DisplayName("describe: each type's settings and default — a multi-value select, a crs-list default, a multi-person parameter")
    fun describeSettingsAndDefaults() {
        configure(
            standaloneTemplate().apply {
                at("parameters")["TARGETS"] =
                    linkedMapOf(
                        "label" to "Targets",
                        "type" to "select",
                        "options" to listOf("api", "ui"),
                        "multiple" to "true",
                        "max-selection" to "2",
                        "default" to listOf("api"),
                    )
                at("parameters")["BS"] =
                    linkedMapOf("label" to "Build system", "type" to "crs-list", "list" to "build-systems", "default" to "GRADLE")
                at("parameters")["REVIEWERS"] = linkedMapOf("label" to "Reviewers", "type" to "person", "multiple" to "true")
                @Suppress("UNCHECKED_CAST")
                ((at("fields")["artifactIds"] as List<MutableMap<String, Any>>).single())["artifactTokens"] =
                    listOf("{{ PLUGIN_CODE | lower }}", "{{ TARGETS }}")
                at("fields.baseConfiguration.build")["buildSystem"] = "{{ BS }}"
                at("fields")["securityChampion"] = listOf("{{ REVIEWERS }}")
            },
        )

        describe()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.parameters[4].name").value("TARGETS"))
            .andExpect(jsonPath("$.parameters[4].multiple").value(true))
            .andExpect(jsonPath("$.parameters[4].maxSelection").value(2))
            .andExpect(jsonPath("$.parameters[4].default", contains("api")))
            .andExpect(jsonPath("$.parameters[5].multiple").value(false))
            .andExpect(jsonPath("$.parameters[5].default", contains("GRADLE")))
            .andExpect(jsonPath("$.parameters[5].values", hasItem("GRADLE")))
            .andExpect(jsonPath("$.parameters[6].type").value("person"))
            .andExpect(jsonPath("$.parameters[6].multiple").value(true))
    }

    @Test
    @DisplayName("describe: a crs-list of labels returns the dictionary's current values, several allowed")
    fun describeCrsList() {
        val label = "tpl-${UUID.randomUUID().toString().take(8)}"
        labelRepository.save(LabelEntity(code = label))
        configure(
            standaloneTemplate().apply {
                at("parameters")["EXTRA"] =
                    linkedMapOf("label" to "Labels", "type" to "crs-list", "list" to "labels", "required" to "false")
                at("fields")["labels"] = listOf("plugin", "{{ EXTRA }}")
            },
        )

        describe()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.parameters[4].list").value("labels"))
            .andExpect(jsonPath("$.parameters[4].multiple").value(true))
            .andExpect(jsonPath("$.parameters[4].values", hasItem(label)))
    }

    @Test
    @DisplayName("describe: a failed or unknown template answers 404")
    fun describeNotFound() {
        configure(standaloneTemplate().apply { remove("title") })

        describe().andExpect(status().isNotFound)
        describe(id = "no-such-template").andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("describe: a caller without ACCESS_COMPONENTS is refused")
    fun describeForbidden() {
        describe(jwt = jwt().jwt { it.claim("preferred_username", "nobody") }).andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("Decision 8: without dryRun it is a dry run — 200, valid, the rendered component and its sources, nothing created")
    fun dryRunByDefault() {
        val code = pluginCode()
        val before = counts()

        components(body(code))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.component.name").value("acme-plugin-${code.lowercase()}"))
            .andExpect(jsonPath("$.component.componentOwner").value("alice"))
            .andExpect(jsonPath("$.sources.name", containsInAnyOrder("CLIENT_CODE", "PLUGIN_CODE")))
            .andExpect(jsonPath("$.sources.labels").isEmpty)
            .andExpect(jsonPath("$.problems").isEmpty)

        assertEquals(before, counts())
        assertFalse(componentRepository.existsByComponentKey("acme-plugin-${code.lowercase()}"))
    }

    @Test
    @DisplayName("Decision 8: an invalid input still answers 200 on a dry run, not valid; parameter problems stop it before rendering")
    fun parameterProblems() {
        components(body(pluginCode = "core", parameters = mapOf("CLIENT_CODE" to listOf("XYZ"), "UNKNOWN" to listOf("x"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.parameterProblems[*].parameter", containsInAnyOrder("CLIENT_CODE", "PLUGIN_CODE", "UNKNOWN")))
            .andExpect(jsonPath("$.parameterProblems[*].check", containsInAnyOrder("P6", "P5", "P1")))
            .andExpect(jsonPath("$.component").doesNotExist())
    }

    @Test
    @DisplayName("a malformed Jira task key answers 400, on a dry run as on a create")
    fun malformedJiraTaskKey() {
        components(body(extra = mapOf("jiraTaskKey" to "not a key"))).andExpect(status().isBadRequest)
        components(body(extra = mapOf("jiraTaskKey" to "not a key")), dryRun = false).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("when several checks fail, the first in order answers: Jira task key 400, template 404, use 403, override 403, path 400")
    fun orderOfChecks() {
        val badKey = mapOf("jiraTaskKey" to "not a key")
        val notOverridable = mapOf("overrides" to mapOf("name" to listOf("my-name")))

        components(body(extra = badKey), id = "no-such-template", jwt = viewerJwt()).andExpect(status().isBadRequest)
        components(body(), id = "no-such-template", jwt = viewerJwt()).andExpect(status().isNotFound)
        components(body(extra = notOverridable), jwt = viewerJwt()).andExpect(status().isForbidden)
        switchable.overrideAllowed = false
        components(body(extra = notOverridable)).andExpect(status().isForbidden)
        switchable.overrideAllowed = true
        components(body(extra = notOverridable)).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("Decision 9: a taken key is a problem on name naming CLIENT_CODE and PLUGIN_CODE")
    fun keyTaken() {
        val code = pluginCode()
        components(body(code), dryRun = false).andExpect(status().isCreated)

        components(body(code))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.problems[0].fields", contains("name")))
            .andExpect(jsonPath("$.problems[0].parameters", containsInAnyOrder("CLIENT_CODE", "PLUGIN_CODE")))
            .andExpect(jsonPath("$.problems[0].templateProblem").value(false))
            .andExpect(jsonPath("$.problems[0].message", startsWith("name: a component with name")))
    }

    @Test
    @DisplayName("Decision 8: a rule problem and the create step's problem are both reported")
    fun ruleAndCreateProblems() {
        val code = pluginCode()
        components(body(code), dryRun = false).andExpect(status().isCreated)
        configure(
            standaloneTemplate().apply {
                put("rules", linkedMapOf("displayName" to linkedMapOf("pattern" to "^Never.*", "message" to "Starts with Never.")))
            },
        )

        components(body(code))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.problems[*].fields[0]", contains("displayName", "name")))
            .andExpect(jsonPath("$.problems[0].message").value("displayName: Starts with Never."))
            .andExpect(jsonPath("$.problems[0].parameters", containsInAnyOrder("PLUGIN_NAME", "CLIENT_CODE")))
    }

    @Test
    @DisplayName("Decision 4: a fixed label missing from the dictionary is a template problem on labels")
    fun unknownFixedLabel() {
        configure(standaloneTemplate().apply { at("fields")["labels"] = listOf("plugin", "no-such-label-${UUID.randomUUID()}") })

        components(body())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.problems[0].fields", contains("labels")))
            .andExpect(jsonPath("$.problems[0].templateProblem").value(true))
    }

    @Test
    @DisplayName("a fixed owner the employee service reports inactive is a template problem")
    fun inactiveFixedOwner() {
        `when`(employees.isActive("gone")).thenReturn(ActiveStatus.INACTIVE)
        configure(
            standaloneTemplate().apply {
                at("parameters").remove("COMPONENT_OWNER")
                at("fields")["componentOwner"] = "gone"
            },
        )

        components(body())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.problems[0].fields", contains("componentOwner")))
            .andExpect(jsonPath("$.problems[0].templateProblem").value(true))
            .andExpect(jsonPath("$.problems[0].message").value("componentOwner 'gone' is not an active employee"))
    }

    @Test
    @DisplayName("a dry run of a failed template answers 404")
    fun dryRunFailedTemplate() {
        configure(standaloneTemplate().apply { remove("title") })

        components(body()).andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("Decision 8: nothing is written after a failing dry run either")
    fun nothingWrittenAfterFailure() {
        configure(standaloneTemplate().apply { at("fields")["labels"] = listOf("plugin", "no-such-label-${UUID.randomUUID()}") })
        val before = counts()

        components(body()).andExpect(jsonPath("$.valid").value(false))

        assertEquals(before, counts())
    }

    @Test
    @DisplayName("Decision 8: a 409 cross-component conflict becomes a problem on the Jira fields")
    fun conflictBecomesProblem() {
        val code = pluginCode()
        val prefix = "acme-plugin-${code.lowercase()}"
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"other-${code.lowercase()}","componentOwner":"alice",""" +
                            """"baseConfiguration":{"build":{"buildSystem":"PROVIDED"},""" +
                            """"jira":{"projectKey":"PLUGINS","versionPrefix":"$prefix","versionFormat":"${'$'}major"}}}""",
                    ),
            ).andExpect(status().isCreated)

        components(body(code))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.problems[0].fields", contains("baseConfiguration.jira.projectKey", "baseConfiguration.jira.versionPrefix")),
            ).andExpect(jsonPath("$.problems[0].parameters", containsInAnyOrder("CLIENT_CODE", "PLUGIN_CODE")))
            .andExpect(jsonPath("$.problems[0].message", startsWith("uniqueness violation: jira project")))
    }

    @Test
    @DisplayName("Decision 8: a 403 editability failure becomes a problem on its field")
    fun editabilityBecomesProblem() {
        withFieldConfig(mapOf("component" to mapOf("clientCode" to mapOf("editable" to "adminOnly")))) {
            components(body(), jwt = editorJwt())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.problems[0].fields", contains("clientCode")))
                .andExpect(jsonPath("$.problems[0].parameters", contains("CLIENT_CODE")))
        }
    }

    @Test
    @DisplayName("Decision 10: a clean create answers 201; the audit row carries the Jira task key and comment")
    fun created() {
        val code = pluginCode()

        val response =
            components(body(code, extra = mapOf("jiraTaskKey" to "PLUGINS-12", "changeComment" to "Onboarding ACME")), dryRun = false)
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("acme-plugin-${code.lowercase()}"))
                .andExpect(jsonPath("$.template").doesNotExist())
                .andReturn()
                .response.contentAsString
        val id = objectMapper.readTree(response)["id"].asText()

        val audit = auditLogRepository.findAll().single { it.entityId == id && it.action == "CREATE" }
        assertEquals("PLUGINS-12", audit.jiraTaskKey)
        assertEquals("Onboarding ACME", audit.changeComment)
    }

    @Test
    @DisplayName("Decision 10: a create without a Jira task key is accepted, as any create is")
    fun createdWithoutJiraTaskKey() {
        components(body(), dryRun = false).andExpect(status().isCreated)
        components(body(extra = mapOf("jiraTaskKey" to "")), dryRun = false).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("Decision 10: any problem answers 422 with the dry-run body and creates nothing")
    fun problemIs422() {
        configure(standaloneTemplate().apply { at("fields")["labels"] = listOf("plugin", "no-such-label-${UUID.randomUUID()}") })
        val before = counts()

        components(body(), dryRun = false)
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.problems[0].fields", contains("labels")))

        assertEquals(before, counts())
    }

    @Test
    @DisplayName("Decision 10: a template removed by a reload answers 404 on create")
    fun removedTemplate() {
        val body = body()
        components(body).andExpect(jsonPath("$.valid").value(true))
        environment.propertySources.remove(OVERRIDES)
        catalog.reload()

        components(body, dryRun = false).andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("the same body without dryRun, then with dryRun=false, answers 200 valid, then 201")
    fun dryRunThenCreate() {
        val body = body()

        components(body).andExpect(status().isOk).andExpect(jsonPath("$.valid").value(true))
        components(body, dryRun = false).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("overrides: an overridable path is created with the override's value")
    fun overridable() {
        val vcsPath = "ssh://git@git.example.com/custom/${UUID.randomUUID()}.git"

        val overrides = mapOf("overrides" to mapOf("baseConfiguration.vcsEntries[0].vcsPath" to listOf(vcsPath)))

        components(body(extra = overrides))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.overridden", contains("baseConfiguration.vcsEntries[0].vcsPath")))
            .andExpect(jsonPath("$.sources['baseConfiguration.vcsEntries[0].vcsPath']").isEmpty)
        components(body(extra = overrides), dryRun = false)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.configurations[0].vcsEntries[0].vcsPath").value(vcsPath))
    }

    @Test
    @DisplayName("overrides: a path the template does not list answers 400 naming it")
    fun notOverridable() {
        val before = counts()

        components(body(extra = mapOf("overrides" to mapOf("name" to listOf("my-name")))), dryRun = false)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", startsWith("name:")))

        assertEquals(before, counts())
    }

    @Test
    @DisplayName("Decision 12: an override from a user the availability rule refuses answers 403")
    fun overrideRefused() {
        switchable.overrideAllowed = false
        val before = counts()

        components(body(extra = mapOf("overrides" to mapOf("baseConfiguration.jira.projectKey" to listOf("OTHER")))), dryRun = false)
            .andExpect(status().isForbidden)

        assertEquals(before, counts())
    }

    @Test
    @DisplayName("overrides: an override that fails a check is reported on the overridden field")
    fun invalidOverride() {
        withFieldConfig(mapOf("jira" to mapOf("projectKey" to mapOf("editable" to "adminOnly")))) {
            components(body(extra = mapOf("overrides" to mapOf("baseConfiguration.jira.projectKey" to listOf("OTHER")))), jwt = editorJwt())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.problems[0].fields", contains("baseConfiguration.jira.projectKey")))
                .andExpect(jsonPath("$.problems[0].templateProblem").value(false))
        }
    }

    @Test
    @DisplayName("a user who may not create components is refused, on a dry run as on a create")
    fun viewerRefused() {
        components(body(), jwt = viewerJwt()).andExpect(status().isForbidden)
        components(body(), dryRun = false, jwt = viewerJwt()).andExpect(status().isForbidden)
    }
}
