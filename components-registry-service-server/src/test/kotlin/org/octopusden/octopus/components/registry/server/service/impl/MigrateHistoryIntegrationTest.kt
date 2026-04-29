package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStatus
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.GitHistoryImportStateRepository
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText
import kotlin.streams.asSequence

@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db")
class MigrateHistoryIntegrationTest {
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
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var stateRepository: GitHistoryImportStateRepository

    @Test
    @Suppress("LongMethod")
    fun `migrate-history end-to-end covers write, 409, reset, parse-error, DELETE, default toRef`() {
        componentRepository.save(ComponentEntity(name = "ARCHIVED_TEST_COMPONENT"))

        // Phase 1: explicit toRef to 1.1 (only c1..c3 in scope).
        val firstBody =
            mvc
                .perform(post("/rest/api/4/admin/migrate-history?toRef=components-registry-1.1").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        val first = objectMapper.readTree(firstBody)
        assertEquals("components-registry-1.1", first["targetRef"].asText())
        assertTrue(first["processedCommits"].asInt() >= 2, "expected >= 2 processed commits, got $first")
        val auditAfterFirst = auditLogRepository.findAll().filter { it.source == "git-history" }
        assertTrue(auditAfterFirst.isNotEmpty(), "expected at least one git-history audit row")
        assertTrue(auditAfterFirst.all { it.correlationId != null }, "every history row carries a commit SHA")
        assertTrue(auditAfterFirst.all { it.changedBy == "Tester <test@example.com>" })
        assertEquals(GitHistoryImportStatus.COMPLETED.name, stateRepository.findById("component-history").orElseThrow().status)

        // Phase 2: repeat without reset → 409, audit_log unchanged.
        mvc
            .perform(post("/rest/api/4/admin/migrate-history?toRef=components-registry-1.1").with(adminJwt()))
            .andExpect(status().isConflict)
        val auditAfterRepeat = auditLogRepository.findAll().filter { it.source == "git-history" }
        assertEquals(auditAfterFirst.size, auditAfterRepeat.size)

        // Phase 3: reset=true, no toRef → auto-resolves to latest tag (components-registry-1.2),
        // covers the unparseable commit (c4) and the DELETE of ARCHIVED_TEST_COMPONENT at c5.
        val secondBody =
            mvc
                .perform(post("/rest/api/4/admin/migrate-history?reset=true").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        val second = objectMapper.readTree(secondBody)
        assertEquals("refs/tags/components-registry-1.2", second["targetRef"].asText())
        assertTrue(
            second["skippedParseError"].asInt() >= 1,
            "c4 is deliberately unparseable, expected skippedParseError >= 1, got $second",
        )

        val auditFinal = auditLogRepository.findAll().filter { it.source == "git-history" }
        val deleteRows = auditFinal.filter { it.action == "DELETE" && (it.oldValue?.get("moduleName") == "ARCHIVED_TEST_COMPONENT") }
        assertTrue(
            deleteRows.isNotEmpty(),
            "expected a DELETE row for ARCHIVED_TEST_COMPONENT at c5, got actions=" +
                auditFinal.map { it.action to it.oldValue?.get("moduleName") },
        )

        // Row ids differ from phase 1 because reset=true wiped and re-inserted everything.
        val firstIds = auditAfterFirst.map { it.id }.toSet()
        val finalIds = auditFinal.map { it.id }.toSet()
        assertNotEquals(firstIds, finalIds, "reset=true should re-create rows with new ids")
    }

    @Test
    fun `reset=true refuses to stomp on an IN_PROGRESS claim`() {
        auditLogRepository.deleteAll()
        stateRepository.deleteAll()
        stateRepository.save(
            GitHistoryImportStateEntity(
                importKey = "component-history",
                targetRef = "refs/tags/components-registry-1.2",
                targetSha = "deadbeef",
                status = GitHistoryImportStatus.IN_PROGRESS.name,
            ),
        )

        mvc
            .perform(post("/rest/api/4/admin/migrate-history?reset=true").with(adminJwt()))
            .andExpect(status().isConflict)

        // Pre-existing IN_PROGRESS row must survive the rejected reset.
        val state = stateRepository.findById("component-history").orElseThrow()
        assertEquals(GitHistoryImportStatus.IN_PROGRESS.name, state.status)
        assertEquals("deadbeef", state.targetSha, "reset must not have wiped the live claim")
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        private lateinit var workDir: Path
        private lateinit var gitRoot: Path

        @BeforeAll
        @JvmStatic
        fun setUp() {
            val base = Files.createTempDirectory("migrate-history-it")
            workDir = base.resolve("work")
            gitRoot = base.resolve("source.git")

            val fixture =
                Paths
                    .get(System.getProperty("user.dir"))
                    .resolve("../test-common/src/test/resources/components-registry/common")
                    .normalize()
            copyTree(fixture, workDir)
            copyTree(fixture, gitRoot)

            Git.init().setDirectory(gitRoot.toFile()).call().use { git ->
                // c1: initial import
                git.add().addFilepattern(".").call()
                git
                    .commit()
                    .setMessage("initial")
                    .setAuthor("Tester", "test@example.com")
                    .call()

                // c2: toggle archived on ARCHIVED_TEST_COMPONENT
                val testComponents = gitRoot.resolve("TestComponents.groovy")
                testComponents.writeText(
                    Files.readString(testComponents).replaceFirst("archived = true", "archived = false"),
                )
                git.add().addFilepattern(".").call()
                git
                    .commit()
                    .setMessage("toggle archived=false on ARCHIVED_TEST_COMPONENT")
                    .setAuthor("Tester", "test@example.com")
                    .call()

                // c3: README-only change — pre-filter must skip
                gitRoot.resolve("README.md").writeText("history test fixture")
                git.add().addFilepattern(".").call()
                val c3 =
                    git
                        .commit()
                        .setMessage("README only, no groovy change")
                        .setAuthor("Tester", "test@example.com")
                        .call()
                git
                    .tag()
                    .setName("components-registry-1.1")
                    .setObjectId(c3)
                    .call()

                // c4: break the DSL so EscrowConfigurationLoader throws → skippedParseError path
                val aggregator = gitRoot.resolve("Aggregator.groovy")
                val savedAggregator = Files.readString(aggregator)
                aggregator.writeText(savedAggregator + "\n}}}invalid<<<groovy>>>syntax{{{\n")
                git.add().addFilepattern(".").call()
                git
                    .commit()
                    .setMessage("break Aggregator.groovy to exercise parse-error path")
                    .setAuthor("Tester", "test@example.com")
                    .call()

                // c5: restore Aggregator + rename ARCHIVED_TEST_COMPONENT so the old name
                // disappears from the snapshot → DELETE action for the seeded UUID.
                aggregator.writeText(savedAggregator)
                testComponents.writeText(
                    Files.readString(testComponents).replaceFirst(
                        "ARCHIVED_TEST_COMPONENT {",
                        "_RENAMED_ARCHIVED_TEST_COMPONENT {",
                    ),
                )
                git.add().addFilepattern(".").call()
                val c5 =
                    git
                        .commit()
                        .setMessage("fix Aggregator and rename ARCHIVED_TEST_COMPONENT (simulates delete)")
                        .setAuthor("Tester", "test@example.com")
                        .call()
                git
                    .tag()
                    .setName("components-registry-1.2")
                    .setObjectId(c5)
                    .call()
            }

            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", workDir.parent.toString())
        }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("components-registry.work-dir") { workDir.toString() }
            registry.add("components-registry.groovy-path") { workDir.toString() }
            registry.add("components-registry.vcs.root") { "file://$gitRoot" }
            registry.add("components-registry.vcs.tag-version-prefix") { "refs/tags/components-registry-" }
            registry.add("pathToConfig") { "file://$workDir" }
        }

        private fun copyTree(
            src: Path,
            dst: Path,
        ) {
            Files.createDirectories(dst)
            Files.walk(src).use { stream ->
                stream.asSequence().forEach { source ->
                    val target = dst.resolve(src.relativize(source).toString())
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }
}
