package org.octopusden.octopus.components.registry.server.service.impl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional

/**
 * CRS-C — full DSL-import → storage → dual-read bridge, on real imported data.
 *
 * Seeds components via the real DSL import from a self-contained copy of the shared `common`
 * fixture (with three extra component blocks appended), then asserts the whole contract in one
 * flow:
 *  - `externalRegistry = "NOT_AVAILABLE"` imports as `skipCommitCheck=true` + a NULL
 *    `vcsExternalRegistry` (the sentinel is never stored);
 *  - the legacy v1–v3 resolver still emits `externalRegistry = "NOT_AVAILABLE"` for that component
 *    (bit-for-bit compat — this is what keeps the "Compat Local Stand" green);
 *  - v4 read exposes `skipCommitCheck=true` + `vcsExternalRegistry=null`;
 *  - the reverse: a real registry name imports untouched (flag stays false) and is emitted verbatim
 *    on both the legacy and v4 surfaces.
 *
 * ft-db (H2) profile with a `@DynamicPropertySource`-overridden groovy source, so no Postgres/Git.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Timeout(120)
@Tag("integration")
class SkipCommitCheckBridgeIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var importService: ImportService

    @Autowired
    private lateinit var componentManagementService: ComponentManagementService

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var databaseComponentRegistryResolver: DatabaseComponentRegistryResolver

    private fun migrate(name: String) {
        val result = importService.migrateComponent(name, dryRun = false)
        assertTrue(result.success, "migration of '$name' must succeed: ${result.message}")
    }

    private fun legacyExternalRegistry(name: String): String? =
        databaseComponentRegistryResolver
            .getResolvedComponentDefinition(name, VERSION)
            ?.vcsSettings
            ?.externalRegistry

    @Test
    @Transactional
    @DisplayName("import NOT_AVAILABLE → skipCommitCheck flag + null registry; legacy re-emits NOT_AVAILABLE; v4 shows the flag")
    fun `not available bridges to flag and back`() {
        migrate(NA_COMPONENT)

        // storage: sentinel became the flag; registry column is null
        val entity = componentRepository.findByComponentKey(NA_COMPONENT)!!
        assertTrue(entity.skipCommitCheck, "NOT_AVAILABLE must import as skipCommitCheck=true")
        assertNull(entity.vcsExternalRegistry, "the sentinel is never stored in vcs_external_registry")

        // legacy v1–v3 surface: still NOT_AVAILABLE (bit-for-bit compat)
        assertEquals("NOT_AVAILABLE", legacyExternalRegistry(NA_COMPONENT))

        // v4 surface: the flag is visible, the registry is null
        val v4 = componentManagementService.getComponentByName(NA_COMPONENT)
        assertTrue(v4.skipCommitCheck, "v4 must expose skipCommitCheck=true")
        assertNull(v4.vcsExternalRegistry, "v4 must NOT surface the sentinel as a registry value")
    }

    @Test
    @Transactional
    @DisplayName("import a real registry name → stored + emitted untouched; flag stays false")
    fun `real registry untouched`() {
        migrate(REAL_COMPONENT)

        val entity = componentRepository.findByComponentKey(REAL_COMPONENT)!!
        assertFalse(entity.skipCommitCheck, "a real registry does not set the flag")
        assertEquals(REAL_REGISTRY, entity.vcsExternalRegistry, "a real registry is stored verbatim")

        assertEquals(REAL_REGISTRY, legacyExternalRegistry(REAL_COMPONENT), "legacy emits the real registry")

        val v4 = componentManagementService.getComponentByName(REAL_COMPONENT)
        assertFalse(v4.skipCommitCheck)
        assertEquals(REAL_REGISTRY, v4.vcsExternalRegistry)
    }

    @Test
    @Transactional
    @DisplayName("import a component with no external registry → flag false, registry null")
    fun `plain component has no flag`() {
        migrate(PLAIN_COMPONENT)

        val entity = componentRepository.findByComponentKey(PLAIN_COMPONENT)!!
        assertFalse(entity.skipCommitCheck)
        assertNull(entity.vcsExternalRegistry)
    }

    @Test
    @Transactional
    @DisplayName("WHISKEY + NOT_AVAILABLE imports (warn, not fail); an unrelated PATCH is grandfathered (no 422)")
    fun `whiskey with sentinel is grandfathered on unrelated patch`() {
        // Import admits the contradiction with a WARNING (Q13 is a v4 write-side rule, not an
        // import hard-fail) — the component lands as skipCommitCheck=true + buildSystem=WHISKEY.
        migrate(WHISKEY_NA_COMPONENT)
        val imported = componentManagementService.getComponentByName(WHISKEY_NA_COMPONENT)
        assertTrue(imported.skipCommitCheck, "NOT_AVAILABLE still bridges to the flag even on WHISKEY")

        // An unrelated PATCH (clientCode only, flag & buildSystem untouched) must NOT be
        // rejected 422 — the WHISKEY rule is change-based, so pre-existing data is grandfathered.
        val updated =
            componentManagementService.updateComponent(
                imported.id,
                ComponentUpdateRequest(version = imported.version, clientCode = "GRANDFATHERED"),
            )
        assertEquals("GRANDFATHERED", updated.clientCode)
        assertTrue(updated.skipCommitCheck, "the pre-existing flag is preserved by an unrelated PATCH")
    }

    companion object {
        private const val NA_COMPONENT = "sccNotAvailable"
        private const val REAL_COMPONENT = "sccRealRegistry"
        private const val PLAIN_COMPONENT = "sccPlain"
        private const val WHISKEY_NA_COMPONENT = "sccWhiskeyNotAvailable"
        private const val REAL_REGISTRY = "some-registry"
        private const val VERSION = "1.0.0"

        private lateinit var workDir: Path

        // Three self-contained component blocks appended to the copied TestComponents.groovy.
        // Component-own defaults only (no version-range block) → import materializes an
        // ALL_VERSIONS BASE row, so any version resolves on the legacy read path.
        private val EXTRA_COMPONENTS =
            """

            "$NA_COMPONENT" {
                componentOwner = "user9"
                groupId = "org.octopusden.octopus.sccna"
                artifactId = "scc-not-available"
                buildSystem = MAVEN
                vcsSettings {
                    vcsUrl = "ssh://git@example/scc-na.git"
                    tag = 'scc-na-${'$'}version'
                    externalRegistry = "NOT_AVAILABLE"
                }
                jira {
                    projectKey = "SCCNA"
                    majorVersionFormat = '${'$'}major'
                    releaseVersionFormat = '${'$'}major.${'$'}minor.${'$'}service'
                }
            }

            "$REAL_COMPONENT" {
                componentOwner = "user9"
                groupId = "org.octopusden.octopus.sccreal"
                artifactId = "scc-real-registry"
                buildSystem = MAVEN
                vcsSettings {
                    vcsUrl = "ssh://git@example/scc-real.git"
                    tag = 'scc-real-${'$'}version'
                    externalRegistry = "$REAL_REGISTRY"
                }
                jira {
                    projectKey = "SCCREAL"
                    majorVersionFormat = '${'$'}major'
                    releaseVersionFormat = '${'$'}major.${'$'}minor.${'$'}service'
                }
            }

            "$PLAIN_COMPONENT" {
                componentOwner = "user9"
                groupId = "org.octopusden.octopus.sccplain"
                artifactId = "scc-plain"
                buildSystem = MAVEN
                vcsSettings {
                    vcsUrl = "ssh://git@example/scc-plain.git"
                    tag = 'scc-plain-${'$'}version'
                }
                jira {
                    projectKey = "SCCPLAIN"
                    majorVersionFormat = '${'$'}major'
                    releaseVersionFormat = '${'$'}major.${'$'}minor.${'$'}service'
                }
            }

            "$WHISKEY_NA_COMPONENT" {
                componentOwner = "user9"
                groupId = "org.octopusden.octopus.sccwhna"
                artifactId = "scc-whiskey-na"
                buildSystem = WHISKEY
                vcsSettings {
                    vcsUrl = "ssh://git@example/scc-whna.git"
                    tag = 'scc-whna-${'$'}version'
                    externalRegistry = "NOT_AVAILABLE"
                }
                jira {
                    projectKey = "SCCWHNA"
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
            workDir = Files.createTempDirectory("skip-commit-bridge")
            copyTree(fixture, workDir)
            // Append our self-contained component blocks to the copied TestComponents.groovy.
            val testComponents = workDir.resolve("TestComponents.groovy")
            Files.writeString(
                testComponents,
                Files.readString(testComponents) + "\n" + EXTRA_COMPONENTS + "\n",
            )
            // The @DynamicPropertySource-set data dir is what application-common.yml's
            // work-dir/groovy-path placeholders resolve against; point them at the temp copy.
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", workDir.parent.toString())
            registry.add("components-registry.work-dir") { workDir.toString() }
            registry.add("components-registry.groovy-path") { workDir.toString() }
            registry.add("pathToConfig") { "file://$workDir" }
        }

        private fun copyTree(src: Path, dst: Path) {
            Files.createDirectories(dst)
            Files.walk(src).use { stream ->
                stream.forEach { source ->
                    val target = dst.resolve(src.relativize(source).toString())
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target)
                    } else {
                        Files.copy(source, target)
                    }
                }
            }
        }
    }
}
