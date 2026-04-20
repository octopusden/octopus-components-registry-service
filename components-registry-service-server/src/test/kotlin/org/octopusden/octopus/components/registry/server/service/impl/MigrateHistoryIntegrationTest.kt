package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStatus
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.GitHistoryImportStateRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
    fun `migrate-history end-to-end - write, 409 on repeat, reset re-runs`() {
        // Seed at least one component we expect to see in history snapshots so findByName resolves.
        componentRepository.save(ComponentEntity(name = "ARCHIVED_TEST_COMPONENT"))

        // Initial import
        val firstBody =
            mvc
                .perform(post("/rest/api/4/admin/migrate-history?toRef=components-registry-1.1"))
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

        val state = stateRepository.findById("component-history").orElseThrow()
        assertEquals(GitHistoryImportStatus.COMPLETED.name, state.status)

        // Repeat without reset → 409, audit_log unchanged.
        mvc
            .perform(post("/rest/api/4/admin/migrate-history?toRef=components-registry-1.1"))
            .andExpect(status().isConflict)
        val auditAfterRepeat = auditLogRepository.findAll().filter { it.source == "git-history" }
        assertEquals(auditAfterFirst.size, auditAfterRepeat.size)

        // reset=true → wipes and re-runs.
        mvc
            .perform(post("/rest/api/4/admin/migrate-history?toRef=components-registry-1.1&reset=true"))
            .andExpect(status().isOk)
        val auditAfterReset = auditLogRepository.findAll().filter { it.source == "git-history" }
        assertEquals(auditAfterFirst.size, auditAfterReset.size)
        // Different row IDs — the rows were physically re-created.
        val firstIds = auditAfterFirst.map { it.id }.toSet()
        val resetIds = auditAfterReset.map { it.id }.toSet()
        assertNotEquals(firstIds, resetIds, "reset=true should re-create rows with new ids")
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

            // Seed workDir with the common fixture so the live ConfigLoader boots, then git-init
            // a SECOND directory as the clone source for the history endpoint.
            val fixture =
                Paths
                    .get(System.getProperty("user.dir"))
                    .resolve("../test-common/src/test/resources/components-registry/common")
                    .normalize()
            copyTree(fixture, workDir)

            // The 'source' registry — this is what the endpoint clones from.
            copyTree(fixture, gitRoot)
            Git.init().setDirectory(gitRoot.toFile()).call().use { git ->
                git.add().addFilepattern(".").call()
                git
                    .commit()
                    .setMessage("initial")
                    .setAuthor("Tester", "test@example.com")
                    .call()

                // c2: toggle archived on ARCHIVED_TEST_COMPONENT (textual: archived = true -> false)
                val tc = gitRoot.resolve("TestComponents.groovy")
                tc.writeText(
                    Files.readString(tc).replaceFirst("archived = true", "archived = false"),
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
