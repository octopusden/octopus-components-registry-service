package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * SYS-099, legacy-DSL side, with `default-source = git`:
 * - before migration a component is Git-sourced and its v1–v3 `testComponent` comes from the
 *   `test-component` label of the resolved DSL model (the DSL has no flag);
 * - migrating it stores `test_component = true` exactly when it carries that label (same rule as
 *   `V11__`), and the DB-sourced read then agrees.
 * The shared DSL fixture is copied and extended, not modified.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
    properties = ["components-registry.default-source=git", "components-registry.auto-migrate=false"],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Timeout(120)
@Tag("integration")
class TestComponentLabelImportIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var importService: ImportService

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var sourceRegistry: ComponentSourceRegistry

    private fun v2(key: String): JsonNode =
        objectMapper.readTree(
            mvc
                .perform(get("/rest/api/2/components/$key"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    @Test
    @DisplayName("SYS-099: git-sourced reads derive the flag from the label; import stores it; DB reads agree")
    fun `SYS-099 label drives the flag on the git path and on import`() {
        assertTrue(sourceRegistry.getSource(LABELLED) == "git")
        assertTrue(v2(LABELLED)["testComponent"].asBoolean(), "git-sourced: label-derived")
        assertFalse(v2(PLAIN)["testComponent"].asBoolean())

        listOf(LABELLED, PLAIN).forEach {
            assertTrue(importService.migrateComponent(it, dryRun = false).success, "migrate $it")
        }
        assertTrue(componentRepository.findByComponentKey(LABELLED)!!.testComponent, "import sets the flag from the label")
        assertFalse(componentRepository.findByComponentKey(PLAIN)!!.testComponent)

        assertTrue(sourceRegistry.getSource(LABELLED) == "db")
        assertTrue(v2(LABELLED)["testComponent"].asBoolean(), "db-sourced: the stored flag")
        assertFalse(v2(PLAIN)["testComponent"].asBoolean())
    }

    companion object {
        private const val LABELLED = "test-sys099-labelled"
        private const val PLAIN = "sys099-unlabelled"

        private lateinit var workDir: Path

        private fun block(
            key: String,
            labels: String,
        ) = """

            "$key" {
                componentOwner = "user9"
                groupId = "org.octopusden.octopus.$key"
                artifactId = "$key"
                buildSystem = MAVEN
                labels = [$labels]
                vcsSettings {
                    vcsUrl = "ssh://git@example/$key.git"
                    tag = '$key-${'$'}version'
                }
                jira {
                    projectKey = "${key.uppercase().filter { it.isLetter() }.take(10)}"
                    majorVersionFormat = '${'$'}major'
                    releaseVersionFormat = '${'$'}major.${'$'}minor.${'$'}service'
                }
            }
            """.trimIndent()

        @JvmStatic
        @DynamicPropertySource
        fun configureSource(registry: DynamicPropertyRegistry) {
            val fixture =
                Paths
                    .get(System.getProperty("user.dir"))
                    .resolve("../test-common/src/test/resources/components-registry/common")
                    .normalize()
            workDir = Files.createTempDirectory("test-component-label")
            copyTree(fixture, workDir)
            val testComponents = workDir.resolve("TestComponents.groovy")
            Files.writeString(
                testComponents,
                Files.readString(testComponents) + "\n" + block(LABELLED, "'test-component'") + "\n" + block(PLAIN, "'java'") + "\n",
            )
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", workDir.parent.toString())
            registry.add("components-registry.work-dir") { workDir.toString() }
            registry.add("components-registry.groovy-path") { workDir.toString() }
            registry.add("pathToConfig") { "file://$workDir" }
        }

        private fun copyTree(
            src: Path,
            dst: Path,
        ) {
            Files.createDirectories(dst)
            Files.walk(src).use { stream ->
                stream.forEach { source ->
                    val target = dst.resolve(src.relativize(source).toString())
                    if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
                }
            }
        }
    }
}
