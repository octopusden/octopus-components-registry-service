package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.dto.v4.ArtifactIdRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BaseConfigurationRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BuildAspectRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BuildToolBeanRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingTokenRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSystemRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionDockerImageRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.TeamcityProjectRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.security.PermissionEvaluator
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.components.registry.server.util.ComponentCodeRenderer
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.Environment
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Stage 5 (cheap field-format checks) — restores the pre-publish single-field
 * validations the old `EscrowConfigValidator` ran but that the v4 write path
 * dropped:
 *
 *  - `clientCode` must match `[A-Z_0-9]+` when present (OLD-VALIDATOR.validateClientCode, audit #16);
 *  - `copyright` must be in the supported list (OLD-VALIDATOR.validateCopyright, audit #21);
 *  - each `artifactIds[].artifactPattern` must be a valid regex (OLD-VALIDATOR.validateArtifactId, audit #9);
 *  - each `buildToolBeans[].beanType` must be specified — the v4 analogue of the old
 *    build-tool per-field requireds (OLD-VALIDATOR.validateBuildConfigurationTools, audit #19).
 *
 * All four are pure `require(...)` checks that run before any DB write, so a
 * unit test with mocked repositories (no Spring context, no DB) is sufficient —
 * same shape as MIG047FieldOverrideWriteGuardTest.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class CheapFieldFormatValidationTest {
    private lateinit var componentRepository: ComponentRepository
    private lateinit var environment: Environment
    private lateinit var fieldConfigService: FieldConfigService
    private lateinit var service: ComponentManagementServiceImpl

    @TempDir
    private lateinit var copyrightDir: Path

    private val existingId = UUID.randomUUID()
    private lateinit var existing: ComponentEntity

    @BeforeEach
    fun setUp() {
        componentRepository = mock(ComponentRepository::class.java)
        environment = mock(Environment::class.java)
        fieldConfigService = mock(FieldConfigService::class.java)

        // A copyright directory holding a single supported file, wired through
        // ConfigHelper.copyrightPath() (env property `components-registry.copyright-path`).
        Files.createFile(copyrightDir.resolve(SUPPORTED_COPYRIGHT))
        doReturn(copyrightDir.toString())
            .`when`(environment)
            .getProperty("components-registry.copyright-path")

        val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")

        service =
            ComponentManagementServiceImpl(
                componentRepository = componentRepository,
                configurationRepository = mock(ComponentConfigurationRepository::class.java),
                componentLabelRepository = mock(ComponentLabelRepository::class.java),
                componentSystemRepository = mock(ComponentSystemRepository::class.java),
                componentRequiredToolRepository = mock(ComponentRequiredToolRepository::class.java),
                componentBuildToolBeanRepository = mock(ComponentBuildToolBeanRepository::class.java),
                mavenArtifactRepository = mock(DistributionMavenArtifactRepository::class.java),
                componentArtifactMappingRepository = mock(ComponentArtifactMappingRepository::class.java),
                componentArtifactMappingTokenRepository = mock(ComponentArtifactMappingTokenRepository::class.java),
                dockerImageRepository = mock(DistributionDockerImageRepository::class.java),
                labelRepository = mock(LabelRepository::class.java),
                systemRepository = mock(SystemRepository::class.java),
                teamcityProjectRepository = mock(TeamcityProjectRepository::class.java),
                toolRepository = mock(ToolRepository::class.java),
                sourceRegistry = mock(ComponentSourceRegistry::class.java),
                applicationEventPublisher = mock(ApplicationEventPublisher::class.java),
                currentUserResolver = mock(CurrentUserResolver::class.java),
                fieldConfigService = fieldConfigService,
                permissionEvaluator = mock(PermissionEvaluator::class.java),
                teamcityProperties = mock(TeamcityProperties::class.java),
                versionRangeFactory = VersionRangeFactory(versionNames),
                numericVersionFactory = NumericVersionFactory(versionNames),
                environment = environment,
                componentCodeRenderer =
                    ComponentCodeRenderer(
                        VersionRangeFactory(versionNames),
                        NumericVersionFactory(versionNames),
                    ),
                employeeDirectory = EmployeeDirectoryService(EmptyObjectProvider()),
                transactionManager = mock(PlatformTransactionManager::class.java),
            )

        // CREATE path: no name collision.
        doReturn(false).`when`(componentRepository).existsByComponentKey(anyString())
        doAnswer { invocation ->
            (invocation.arguments[0] as ComponentEntity).apply {
                if (id == null) id = UUID.randomUUID()
                artifactMappings.forEach { m ->
                    if (m.id == null) m.id = UUID.randomUUID()
                    m.tokens.forEach { if (it.id == null) it.id = UUID.randomUUID() }
                }
                configurations.forEach { config ->
                    if (config.id == null) config.id = UUID.randomUUID()
                    config.buildToolBeans.forEach { if (it.id == null) it.id = UUID.randomUUID() }
                }
            }
        }.`when`(componentRepository).saveAndFlush(any(ComponentEntity::class.java))

        // UPDATE path: an existing component with a matching optimistic-lock version.
        // FieldConfigService is a Mockito mock → isHidden(...) defaults to false,
        // so update writes are NOT silently stripped.
        existing =
            ComponentEntity(
                id = existingId,
                componentKey = "cheap-fixture",
                version = 0L,
                // Required by the foundation contract (componentOwner non-blank). Without it
                // a buildToolBeans/artifactIds-only PATCH would 400 on the owner check (which
                // runs before the beanType/artifactId checks) instead of the field under test.
                componentOwner = "owner1",
            )
        doReturn(Optional.of(existing)).`when`(componentRepository).findById(existingId)
    }

    private fun minimalCreate(
        name: String = "cheap-create",
        componentOwner: String? = "owner1",
        clientCode: String? = null,
        copyright: String? = null,
        artifactIds: List<ArtifactIdRequest> = emptyList(),
        buildToolBeans: List<BuildToolBeanRequest>? = null,
    ) = ComponentCreateRequest(
        name = name,
        componentOwner = componentOwner,
        clientCode = clientCode,
        copyright = copyright,
        artifactIds = artifactIds,
        baseConfiguration =
            BaseConfigurationRequest(
                build = BuildAspectRequest(buildSystem = "MAVEN"),
                buildToolBeans = buildToolBeans,
            ),
    )

    private fun minimalUpdate(
        clientCode: String? = null,
        copyright: String? = null,
        artifactIds: List<ArtifactIdRequest>? = null,
        buildToolBeans: List<BuildToolBeanRequest>? = null,
    ) = ComponentUpdateRequest(
        version = 0L,
        clientCode = clientCode,
        copyright = copyright,
        artifactIds = artifactIds,
        baseConfiguration =
            if (buildToolBeans != null) {
                BaseConfigurationRequest(buildToolBeans = buildToolBeans)
            } else {
                null
            },
    )

    private fun assertFieldPrefixed(
        field: String,
        block: () -> Unit,
    ) {
        val ex = assertThrows(IllegalArgumentException::class.java, block)
        assertTrue(
            ex.message!!.startsWith(field),
            "message must start with the field name '$field'; got: '${ex.message}'",
        )
    }

    // ---------------------------------------------------------------------
    // clientCode  —  [A-Z_0-9]+   (audit #16)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("CREATE rejects clientCode not matching [A-Z_0-9]+")
    fun create_rejects_badClientCode() {
        assertFieldPrefixed("clientCode") {
            service.createComponent(minimalCreate(clientCode = "bad-code!"))
        }
    }

    @Test
    @DisplayName("PATCH rejects clientCode not matching [A-Z_0-9]+")
    fun patch_rejects_badClientCode() {
        assertFieldPrefixed("clientCode") {
            service.updateComponent(existingId, minimalUpdate(clientCode = "bad code"))
        }
    }

    // ---------------------------------------------------------------------
    // copyright  —  supported list   (audit #21)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("CREATE rejects copyright outside the supported list")
    fun create_rejects_unsupportedCopyright() {
        assertFieldPrefixed("copyright") {
            service.createComponent(minimalCreate(copyright = "NOT_A_KNOWN_COPYRIGHT"))
        }
    }

    @Test
    @DisplayName("PATCH rejects copyright outside the supported list")
    fun patch_rejects_unsupportedCopyright() {
        assertFieldPrefixed("copyright") {
            service.updateComponent(existingId, minimalUpdate(copyright = "NOT_A_KNOWN_COPYRIGHT"))
        }
    }

    @Test
    @DisplayName("CREATE requires copyright for explicit+external component when copyright path is configured")
    fun create_requiresCopyrightForExplicitExternal() {
        val request =
            minimalCreate().copy(
                releaseManager = listOf("releaseManager"),
                securityChampion = listOf("securityChampion"),
                distributionExplicit = true,
                distributionExternal = true,
            )

        assertFieldPrefixed("copyright") {
            service.createComponent(request)
        }
    }

    @Test
    @DisplayName("PATCH validates final-state copyright requiredness for explicit+external component")
    fun patch_requiresCopyrightForExplicitExternal() {
        existing.distributionExplicit = true
        existing.distributionExternal = true
        existing.replaceReleaseManagerUsernames(listOf("releaseManager"))
        existing.replaceSecurityChampionUsernames(listOf("securityChampion"))

        assertFieldPrefixed("copyright") {
            service.updateComponent(existingId, minimalUpdate(clientCode = "VALID_CODE"))
        }
    }

    @Test
    @DisplayName("copyright error lists available values in deterministic order")
    fun copyright_errorListsSupportedValuesSorted() {
        Files.createFile(copyrightDir.resolve("z-license.txt"))
        Files.createFile(copyrightDir.resolve("a-license.txt"))

        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                service.createComponent(minimalCreate(copyright = "NOT_A_KNOWN_COPYRIGHT"))
            }
        assertTrue(
            ex.message!!.contains("Available values are [a-license.txt, $SUPPORTED_COPYRIGHT, z-license.txt]"),
            "available copyright values must be sorted; got: '${ex.message}'",
        )
    }

    // ---------------------------------------------------------------------
    // artifactId pattern  —  valid regex   (audit #9)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("CREATE rejects an EXPLICIT artifact token with forbidden (regex) characters")
    fun create_rejects_invalidArtifactToken() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                service.createComponent(
                    minimalCreate(
                        artifactIds = listOf(
                            ArtifactIdRequest(
                                groupPattern = "org.example",
                                mode = "EXPLICIT",
                                artifactTokens = listOf("[unclosed"),
                            ),
                        ),
                    ),
                )
            }
        assertTrue(ex.message!!.startsWith("artifactIds"), "got: ${ex.message}")
    }

    @Test
    @DisplayName("PATCH rejects an EXPLICIT artifact token with forbidden characters")
    fun patch_rejects_invalidArtifactToken() {
        assertFieldPrefixed("artifactIds") {
            service.updateComponent(
                existingId,
                minimalUpdate(
                    artifactIds = listOf(
                        ArtifactIdRequest(groupPattern = "org.example", mode = "EXPLICIT", artifactTokens = listOf("(")),
                    ),
                ),
            )
        }
    }

    // ---------------------------------------------------------------------
    // buildToolBeans per-field requireds   (audit #19)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("CREATE rejects a buildToolBean with a blank beanType")
    fun create_rejects_blankBeanType() {
        assertFieldPrefixed("beanType") {
            service.createComponent(
                minimalCreate(buildToolBeans = listOf(BuildToolBeanRequest(beanType = "   "))),
            )
        }
    }

    @Test
    @DisplayName("PATCH rejects a buildToolBean with a blank beanType")
    fun patch_rejects_blankBeanType() {
        assertFieldPrefixed("beanType") {
            service.updateComponent(
                existingId,
                minimalUpdate(buildToolBeans = listOf(BuildToolBeanRequest(beanType = ""))),
            )
        }
    }

    @Test
    @DisplayName("valid clientCode, copyright, artifactId regex, and beanType pass validation (green-path)")
    fun greenPath_validationPasses() {
        doReturn(false).`when`(fieldConfigService).isHidden(anyString())

        // CREATE green path
        val createReq =
            minimalCreate(
                clientCode = "VALID_CODE_1",
                copyright = SUPPORTED_COPYRIGHT,
                artifactIds = listOf(
                    ArtifactIdRequest(groupPattern = "org.example", mode = "EXPLICIT", artifactTokens = listOf("svc-core")),
                ),
                buildToolBeans = listOf(BuildToolBeanRequest(beanType = "oracleDatabase")),
            )
        org.junit.jupiter.api.assertDoesNotThrow {
            service.createComponent(createReq)
        }

        // PATCH green path
        val updateReq =
            minimalUpdate(
                clientCode = "VALID_CODE_2",
                copyright = SUPPORTED_COPYRIGHT,
                artifactIds = listOf(
                    ArtifactIdRequest(groupPattern = "org.example", mode = "EXPLICIT", artifactTokens = listOf("another-svc")),
                ),
                buildToolBeans = listOf(BuildToolBeanRequest(beanType = "odbc")),
            )
        org.junit.jupiter.api.assertDoesNotThrow {
            service.updateComponent(existingId, updateReq)
        }
    }

    @Test
    @DisplayName("validation is skipped for hidden fields (hidden-field-skip)")
    fun hiddenFields_skipValidation() {
        doReturn(true).`when`(fieldConfigService).isHidden("component.clientCode")
        doReturn(true).`when`(fieldConfigService).isHidden("component.copyright")

        // Create/Update with invalid clientCode and copyright should not throw because they are hidden and skipped.
        val createReq =
            minimalCreate(
                clientCode = "invalid-code-due-to-lowercase!",
                copyright = "INVALID_UNSUPPORTED_COPYRIGHT",
            )
        org.junit.jupiter.api.assertDoesNotThrow {
            service.createComponent(createReq)
        }

        val updateReq =
            minimalUpdate(
                clientCode = "invalid-code-due-to-spaces ",
                copyright = "INVALID_COPYRIGHT_2",
            )
        org.junit.jupiter.api.assertDoesNotThrow {
            service.updateComponent(existingId, updateReq)
        }
    }

    companion object {
        private const val SUPPORTED_COPYRIGHT = "supported-copyright.txt"
    }
}
