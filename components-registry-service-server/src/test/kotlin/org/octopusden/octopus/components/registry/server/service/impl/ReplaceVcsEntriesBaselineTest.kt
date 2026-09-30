package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryRequest
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ONB-001 baseline (characterization): how the v4 write path stores VCS entries. `replaceVcsEntries`
 * backs every VCS write (create, PATCH with `vcsEntries`, `vcs.settings` field overrides), so it is
 * driven directly over an in-memory configuration row.
 *
 * Pinned: a missing request `name` is stored as `"main"`, with no uniqueness check (two unnamed entries
 * both become `"main"`); the list is replaced wholesale on every write (prior entity instances are
 * dropped and new ones carry no id, so the DB assigns fresh ids through `orphanRemoval` + generated
 * UUIDs); `sortOrder` is the request list index.
 *
 * ONB-001 placement rules (`vcsEntries[<i>].<field>: ` errors) are driven here too; the two-unnamed-entries
 * baseline is superseded by the rule that at most one entry of a row goes without a `checkoutDirectory`.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ReplaceVcsEntriesBaselineTest {
    private lateinit var service: ComponentManagementServiceImpl
    private lateinit var replaceVcsEntries: Method

    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")

    @BeforeEach
    fun setUp() {
        service = ComponentManagementServiceImpl(
            componentRepository = mock(ComponentRepository::class.java),
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
            fieldConfigService = mock(FieldConfigService::class.java),
            permissionEvaluator = mock(PermissionEvaluator::class.java),
            teamcityProperties = mock(TeamcityProperties::class.java),
            versionRangeFactory = VersionRangeFactory(versionNames),
            numericVersionFactory = NumericVersionFactory(versionNames),
            environment = mock(Environment::class.java),
            componentCodeRenderer = mock(ComponentCodeRenderer::class.java),
            employeeDirectory = mock(EmployeeDirectoryService::class.java),
            transactionManager = mock(PlatformTransactionManager::class.java),
        )
        replaceVcsEntries = ComponentManagementServiceImpl::class.java
            .getDeclaredMethod(
                "replaceVcsEntries",
                ComponentConfigurationEntity::class.java,
                List::class.java,
            ).apply { isAccessible = true }
    }

    private fun baseRow() =
        ComponentConfigurationEntity(
            component = ComponentEntity(id = UUID.randomUUID(), componentKey = "test-component-a"),
            versionRange = "(,0),[0,)",
            overriddenAttribute = null,
            rowType = "BASE",
        )

    private fun write(
        config: ComponentConfigurationEntity,
        vararg requests: VcsEntryRequest,
    ) {
        replaceVcsEntries.invoke(service, config, requests.toList())
    }

    @Test
    @DisplayName("ONB-001 baseline: v4 VCS entry without name is stored as \"main\"; an entry with a checkoutDirectory is named by it")
    fun `baseline missing name is stored as main`() {
        val config = baseRow()
        write(
            config,
            VcsEntryRequest(vcsPath = REPO_A),
            VcsEntryRequest(name = "repo-b", vcsPath = REPO_B, checkoutDirectory = "repo-b"),
        )

        assertEquals(listOf("main", "repo-b"), config.vcsEntries.map { it.name })
    }

    @Test
    @DisplayName("ONB-001 baseline: every v4 VCS write recreates all entries; sortOrder is the request index")
    fun `baseline every write recreates entries in request order`() {
        val config = baseRow()
        val stored =
            VcsSettingsEntryEntity(id = UUID.randomUUID(), componentConfiguration = config, name = "repo-a", vcsPath = REPO_A)
        config.vcsEntries.add(stored)

        // Same repository re-sent unchanged, plus a second one ahead of it.
        write(
            config,
            VcsEntryRequest(name = "repo-b", vcsPath = REPO_B, branch = "master"),
            VcsEntryRequest(name = "second", vcsPath = REPO_A, checkoutDirectory = "second"),
        )

        assertEquals(listOf(REPO_B, REPO_A), config.vcsEntries.map { it.vcsPath })
        assertEquals(listOf(0, 1), config.vcsEntries.map { it.sortOrder })
        assertTrue(config.vcsEntries.none { it === stored }, "the stored entity is dropped, not updated in place")
        config.vcsEntries.forEach { assertNull(it.id, "a recreated entry carries no id; the DB assigns a new one") }
    }

    private fun assertRejected(
        expectedPrefix: String,
        vararg requests: VcsEntryRequest,
        config: ComponentConfigurationEntity = baseRow(),
    ) {
        val before = config.vcsEntries.toList()
        val thrown = assertThrows(InvocationTargetException::class.java) { write(config, *requests) }.targetException
        assertTrue(thrown is IllegalArgumentException, "expected IllegalArgumentException, got $thrown")
        assertTrue(thrown.message!!.startsWith(expectedPrefix), "expected '$expectedPrefix…', got '${thrown.message}'")
        assertEquals(before, config.vcsEntries.toList(), "a rejected write leaves the row untouched")
    }

    private fun stored(
        config: ComponentConfigurationEntity,
        vararg names: String,
    ) = config.apply {
        names.forEachIndexed { i, name ->
            vcsEntries.add(
                VcsSettingsEntryEntity(
                    componentConfiguration = this,
                    name = name,
                    vcsPath = "ssh://git@example.test/proj/stored-$i.git",
                    sortOrder = i,
                    checkoutDirectory = name.takeIf { i > 0 },
                ),
            )
        }
    }

    @Test
    @DisplayName("ONB-001: a second entry at the checkout root (no checkoutDirectory) is rejected on the later one")
    fun `two entries at the checkout root`() {
        assertRejected("vcsEntries[1].checkoutDirectory: ", VcsEntryRequest(vcsPath = REPO_A), VcsEntryRequest(vcsPath = REPO_B))
    }

    @Test
    @DisplayName("ONB-001 rev. 3: the first entry may carry a checkoutDirectory when a later entry is at the checkout root")
    fun `first entry placed, later entry at the root`() {
        val config = baseRow()
        write(config, VcsEntryRequest(vcsPath = REPO_A, checkoutDirectory = "core"), VcsEntryRequest(vcsPath = REPO_B))

        assertEquals(listOf("core", null), config.vcsEntries.map { it.checkoutDirectory })
    }

    @Test
    @DisplayName("ONB-001: a checkoutDirectory equal to the name of the entry at the checkout root (ignoring case) is rejected")
    fun `checkout directory colliding with the root entry name`() {
        assertRejected(
            "vcsEntries[1].checkoutDirectory: ",
            VcsEntryRequest(vcsPath = REPO_A),
            VcsEntryRequest(vcsPath = REPO_B, checkoutDirectory = "Main"),
        )
    }

    @Test
    @DisplayName("ONB-001: two entries with the same checkoutDirectory (ignoring case) are rejected on the later one")
    fun `duplicate checkout directories`() {
        assertRejected(
            "vcsEntries[2].checkoutDirectory: ",
            VcsEntryRequest(vcsPath = REPO_A),
            VcsEntryRequest(vcsPath = REPO_B, checkoutDirectory = "feature"),
            VcsEntryRequest(vcsPath = REPO_C, checkoutDirectory = "FEATURE"),
        )
    }

    @Test
    @DisplayName("ONB-001: checkoutDirectory must be one segment without a leading dot and not reserved")
    fun `invalid checkout directories`() {
        listOf(".hidden", "a/b", "a b", "a=>b", "report-templates", "sonar-config", "target", "Target", "sonar-report").forEach { dir ->
            assertRejected(
                "vcsEntries[1].checkoutDirectory: ",
                VcsEntryRequest(vcsPath = REPO_A),
                VcsEntryRequest(vcsPath = REPO_B, checkoutDirectory = dir),
            )
        }
    }

    @Test
    @DisplayName("ONB-001: sourcePath must be relative with plain segments")
    fun `invalid source paths`() {
        // Also TeamCity checkout-rule syntax (`=>`, `+:`), a line break, and a fullwidth-unicode lookalike of `abc`.
        val paths = listOf("../other", "/abs", "a b", ".", "a//b", "a/", "a/../b", "%param%", "a=>b", "+:x", "a\nb", "\uFF41\uFF42\uFF43")
        paths.forEach { path ->
            assertRejected("vcsEntries[0].sourcePath: ", VcsEntryRequest(vcsPath = REPO_A, sourcePath = path))
        }
    }

    @Test
    @DisplayName("ONB-001: checkoutDirectory and sourcePath are at most 255 characters (VARCHAR(255))")
    fun `placement length bound`() {
        val long = "a".repeat(256)
        assertRejected(
            "vcsEntries[1].checkoutDirectory: is 256 characters long; a Checkout Directory can have at most 255",
            VcsEntryRequest(vcsPath = REPO_A),
            VcsEntryRequest(vcsPath = REPO_B, checkoutDirectory = long),
        )
        assertRejected(
            "vcsEntries[0].sourcePath: is 256 characters long; a Source Path can have at most 255",
            VcsEntryRequest(vcsPath = REPO_A, sourcePath = long),
        )

        val config = baseRow()
        write(config, VcsEntryRequest(vcsPath = REPO_A, sourcePath = "a".repeat(255)))
        assertEquals(
            255,
            config.vcsEntries
                .single()
                .sourcePath!!
                .length,
        )
    }

    @Test
    @DisplayName("ONB-001: the same repository (Git ignoring case) and sourcePath twice is rejected on the later entry")
    fun `duplicate repository and source path`() {
        assertRejected(
            "vcsEntries[2].sourcePath: ",
            VcsEntryRequest(vcsPath = REPO_A, sourcePath = "mapper"),
            VcsEntryRequest(vcsPath = REPO_B, checkoutDirectory = "feature"),
            VcsEntryRequest(vcsPath = REPO_A.uppercase(), sourcePath = "mapper", checkoutDirectory = "other"),
        )
    }

    @Test
    @DisplayName("ONB-001: valid placement is accepted: one repository split by sourcePath, nested sourcePath, dotted directory")
    fun `valid placement accepted`() {
        val config = baseRow()
        write(
            config,
            VcsEntryRequest(vcsPath = REPO_A, sourcePath = "mapper"),
            VcsEntryRequest(vcsPath = REPO_A, sourcePath = "data/sub.dir", checkoutDirectory = "feature"),
            VcsEntryRequest(vcsPath = REPO_B, checkoutDirectory = "v1.2_x-y"),
        )

        assertEquals(listOf(null, "feature", "v1.2_x-y"), config.vcsEntries.map { it.checkoutDirectory })
        assertEquals(listOf("mapper", "data/sub.dir", null), config.vcsEntries.map { it.sourcePath })
    }

    @Test
    @DisplayName("ONB-001: a migrated row whose names are unusable fails its next unchanged save on vcsEntries[1].checkoutDirectory")
    fun `migrated unusable names fail the next save`() {
        listOf(listOf("main", "main"), listOf("main", "a/b")).forEach { names ->
            val config = stored(baseRow(), *names.toTypedArray())
            assertRejected(
                "vcsEntries[1].checkoutDirectory: ",
                *config.vcsEntries
                    .map { VcsEntryRequest(name = it.name, vcsPath = it.vcsPath, checkoutDirectory = it.checkoutDirectory) }
                    .toTypedArray(),
                config = config,
            )
        }
    }

    private fun withBwd(bwd: String?) = baseRow().apply { buildWorkingDirectory = bwd }

    private fun placed(vararg dirs: String?) =
        dirs.mapIndexed { i, d -> VcsEntryRequest(vcsPath = "ssh://git@example.test/proj/r$i.git", checkoutDirectory = d) }

    @Test
    @DisplayName("ONB-001 rev. 3: a Build Working Directory inside a placed entry or below the checkout root is accepted")
    fun `build working directory accepted`() {
        write(withBwd("core/mapper"), *placed("core", "feature").toTypedArray())
        write(withBwd("mapper"), *placed("core", null).toTypedArray())
        write(withBwd("core"), *placed("core").toTypedArray())
        write(withBwd(null), *placed("core", null).toTypedArray())
        write(withBwd(null))
    }

    @Test
    @DisplayName("ONB-001 rev. 3: a Build Working Directory outside every placed entry, or missing when all are placed, is rejected")
    fun `build working directory rejected`() {
        assertRejected("buildWorkingDirectory: ", *placed("core", "feature").toTypedArray(), config = withBwd("other"))
        assertRejected("buildWorkingDirectory: ", *placed("core", "feature").toTypedArray(), config = withBwd("Core/x"))
        assertRejected(
            "buildWorkingDirectory: required: every VCS root has a Checkout Directory, so set the folder the build runs in",
            *placed("core", "feature").toTypedArray(),
            config = withBwd(null),
        )
        assertRejected("buildWorkingDirectory: ", config = withBwd("core"))
        listOf("../x", "/abs", "a//b", "a b", "a=>b", "a".repeat(256)).forEach { bwd ->
            assertRejected("buildWorkingDirectory: ", *placed("core", null).toTypedArray(), config = withBwd(bwd))
        }
    }

    @Test
    @DisplayName("ONB-001 rev. 3: entries changed under a stored Build Working Directory are checked against it; entry rules come first")
    fun `stored build working directory checked against new entries`() {
        assertRejected("buildWorkingDirectory: ", *placed("a", "b").toTypedArray(), config = withBwd("core"))
        assertRejected("vcsEntries[1].checkoutDirectory: ", *placed(null, null).toTypedArray(), config = withBwd("../x"))
    }

    private fun messageOf(
        vararg requests: VcsEntryRequest,
        config: ComponentConfigurationEntity = baseRow(),
    ): String = assertThrows(InvocationTargetException::class.java) { write(config, *requests) }.targetException.message!!

    @Test
    @DisplayName("ONB-001: placement errors name VCS roots 1-based with their repository and say what to do")
    fun `user-facing placement messages`() {
        val tds = "ssh://git@example.test/proj/tdsecure.git"
        val plugins = "ssh://git@example.test/proj/tdsecure-plugins.git"
        assertEquals(
            "vcsEntries[1].checkoutDirectory: required: VCS root 1 (tdsecure) is already checked out at the checkout root, and only one " +
                "VCS root can be. Set a Checkout Directory for this VCS root, a folder name such as 'tdsecure-plugins', " +
                "or give one to VCS root 1.",
            messageOf(VcsEntryRequest(vcsPath = tds), VcsEntryRequest(vcsPath = plugins)),
        )
        assertEquals(
            "vcsEntries[1].checkoutDirectory: Checkout Directory 'UI' is already used by VCS root 1 (tdsecure). " +
                "Each VCS root needs its own " +
                "folder, and the comparison ignores case. Choose another folder name.",
            messageOf(
                VcsEntryRequest(vcsPath = tds, checkoutDirectory = "ui"),
                VcsEntryRequest(vcsPath = plugins, checkoutDirectory = "UI"),
            ),
        )
        assertEquals(
            "vcsEntries[1].checkoutDirectory: Checkout Directory 'Main' is already the name of VCS root 1 (tdsecure), " +
                "which is checked out " +
                "at the checkout root. Names must be unique, ignoring case. Choose another folder name.",
            messageOf(VcsEntryRequest(vcsPath = tds), VcsEntryRequest(vcsPath = plugins, checkoutDirectory = "Main")),
        )
        assertEquals(
            "vcsEntries[2].sourcePath: VCS root 1 (tdsecure) already checks out the same repository and Source Path ('core'). " +
                "Change the Source Path, or remove one of the VCS roots.",
            messageOf(
                VcsEntryRequest(vcsPath = tds, sourcePath = "core"),
                VcsEntryRequest(vcsPath = plugins, checkoutDirectory = "plugins"),
                VcsEntryRequest(vcsPath = tds, sourcePath = "core", checkoutDirectory = "again"),
            ),
        )
        assertEquals(
            "vcsEntries[0].checkoutDirectory: 'a/b' is not a valid Checkout Directory: use a single folder name of letters, digits, " +
                "'.', '_' or '-' that does not start with '.', for example 'app'.",
            messageOf(VcsEntryRequest(vcsPath = tds, checkoutDirectory = "a/b"), VcsEntryRequest(vcsPath = plugins)),
        )
        assertEquals(
            "vcsEntries[0].checkoutDirectory: 'Target' is reserved: the build tooling uses that folder at the checkout root. " +
                "Choose another folder name, for example 'tdsecure'.",
            messageOf(VcsEntryRequest(vcsPath = tds, checkoutDirectory = "Target"), VcsEntryRequest(vcsPath = plugins)),
        )
        assertEquals(
            "vcsEntries[0].sourcePath: '../x' is not a valid Source Path: use a relative path of '/'-separated folder names " +
                "(letters, digits, '.', '_', '-'; no '.' or '..'), for example 'services/api'.",
            messageOf(VcsEntryRequest(vcsPath = tds, sourcePath = "../x")),
        )
    }

    @Test
    @DisplayName("ONB-001: the suggested folder name is itself a valid, free Checkout Directory")
    fun `suggested folder name is valid`() {
        val target = "ssh://git@example.test/proj/target.git"
        assertTrue(messageOf(VcsEntryRequest(vcsPath = REPO_A), VcsEntryRequest(vcsPath = "")).contains("a folder name such as 'app',"))
        assertTrue(messageOf(VcsEntryRequest(vcsPath = REPO_A), VcsEntryRequest(vcsPath = target)).contains("a folder name such as 'app',"))
        assertTrue(
            messageOf(VcsEntryRequest(vcsPath = target, checkoutDirectory = "Target"), VcsEntryRequest(vcsPath = REPO_A))
                .endsWith("Choose another folder name, for example 'app'."),
        )
        // The derived name is already another VCS root's Checkout Directory: suggest a free one.
        assertTrue(
            messageOf(
                VcsEntryRequest(vcsPath = REPO_A, checkoutDirectory = "repo-b"),
                VcsEntryRequest(vcsPath = REPO_C),
                VcsEntryRequest(vcsPath = REPO_B),
            ).contains("a folder name such as 'app',"),
        )
    }

    @Test
    @DisplayName("ONB-001: Build Working Directory errors say what is wrong and what to do")
    fun `user-facing build working directory messages`() {
        assertEquals(
            "buildWorkingDirectory: required: every VCS root has a Checkout Directory, so set the folder the build runs in, " +
                "for example 'core' or 'core/app'.",
            messageOf(*placed("core", "feature").toTypedArray(), config = withBwd(null)),
        )
        assertEquals(
            "buildWorkingDirectory: 'other/app' is not inside any checked-out VCS root. Start it with one of the Checkout Directories: " +
                "core, feature; or check one VCS root out at the checkout root (no Checkout Directory) to allow any folder.",
            messageOf(*placed("core", "feature").toTypedArray(), config = withBwd("other/app")),
        )
        assertEquals(
            "buildWorkingDirectory: the row has no VCS roots, so the build has no folder to run in. Add a VCS root, " +
                "or clear the Build Working Directory.",
            messageOf(config = withBwd("core")),
        )
        assertEquals(
            "buildWorkingDirectory: '../x' is not a valid Build Working Directory: use a relative path of '/'-separated folder names " +
                "(letters, digits, '.', '_', '-'; no '.' or '..'), for example 'core/app'.",
            messageOf(*placed("core", null).toTypedArray(), config = withBwd("../x")),
        )
    }

    private fun names(config: ComponentConfigurationEntity) = config.vcsEntries.map { it.name }

    private fun echo(config: ComponentConfigurationEntity) =
        config.vcsEntries
            .map { VcsEntryRequest(name = it.name, vcsPath = it.vcsPath, checkoutDirectory = it.checkoutDirectory) }
            .toTypedArray()

    @Test
    @DisplayName("ONB-001: a secondary entry is named by its checkoutDirectory; the request name is ignored")
    fun `secondary name is checkout directory`() {
        val config = baseRow()
        write(
            config,
            VcsEntryRequest(name = "x", vcsPath = REPO_A),
            VcsEntryRequest(name = "other", vcsPath = REPO_B, checkoutDirectory = "feature"),
        )

        assertEquals(listOf("main", "feature"), names(config))
    }

    @Test
    @DisplayName("ONB-001 rev. 3: an entry at the checkout root keeps the name of its repository's previous entry; re-pointed it is main")
    fun `root entry keeps its repository's name`() {
        val config = stored(baseRow(), "core")
        val repo = config.vcsEntries.single().vcsPath
        write(config, VcsEntryRequest(vcsPath = repo), VcsEntryRequest(vcsPath = REPO_B, checkoutDirectory = "feature"))
        assertEquals(listOf("core", "feature"), names(config))

        write(config, *echo(config))
        assertEquals(listOf("core", "feature"), names(config))

        write(config, VcsEntryRequest(vcsPath = REPO_C), VcsEntryRequest(vcsPath = REPO_B, checkoutDirectory = "feature"))
        assertEquals(listOf("main", "feature"), names(config))
    }

    @Test
    @DisplayName("ONB-001 rev. 3: a single entry on the same repository keeps its name, the request name is ignored")
    fun `single entry keeps name`() {
        val config = stored(baseRow(), "core")
        write(config, VcsEntryRequest(name = "renamed", vcsPath = config.vcsEntries.single().vcsPath))

        assertEquals(listOf("core"), names(config))
    }

    @Test
    @DisplayName("ONB-001 rev. 3: the entry at the checkout root may be listed second and keeps its repository's name")
    fun `root entry listed second`() {
        val config = stored(baseRow(), "app", "gateway")
        val (app, gateway) = config.vcsEntries.map { it.vcsPath }
        write(config, VcsEntryRequest(vcsPath = app, checkoutDirectory = "app"), VcsEntryRequest(vcsPath = gateway))

        assertEquals(listOf("app", "gateway"), names(config))
        assertEquals(listOf("app", null), config.vcsEntries.map { it.checkoutDirectory })
    }

    @Test
    @DisplayName("ONB-001 rev. 3: the repository is matched ignoring case for Git; the lowest previous position wins")
    fun `repository matched ignoring case, lowest position`() {
        val config = baseRow()
        config.vcsEntries.add(VcsSettingsEntryEntity(componentConfiguration = config, name = "first", vcsPath = REPO_A, sortOrder = 0))
        config.vcsEntries.add(
            VcsSettingsEntryEntity(
                componentConfiguration = config,
                name = "second",
                vcsPath = REPO_A,
                sortOrder = 1,
                checkoutDirectory = "second",
            ),
        )
        write(config, VcsEntryRequest(vcsPath = REPO_A.uppercase()))

        assertEquals(listOf("first"), names(config))
    }

    @Test
    @DisplayName("ONB-001 rev. 3: one repository placed and at the root saves unchanged; the root entry keeps its own name")
    fun `same repository placed and at the root echo`() {
        val config = baseRow()
        config.vcsEntries.add(
            VcsSettingsEntryEntity(componentConfiguration = config, name = "ui", vcsPath = REPO_A, sortOrder = 0, checkoutDirectory = "ui"),
        )
        config.vcsEntries.add(
            VcsSettingsEntryEntity(componentConfiguration = config, name = "main", vcsPath = REPO_A, sortOrder = 1, sourcePath = "core"),
        )
        write(
            config,
            VcsEntryRequest(vcsPath = REPO_A, checkoutDirectory = "ui"),
            VcsEntryRequest(vcsPath = REPO_A, sourcePath = "core"),
        )

        assertEquals(listOf("ui", "main"), names(config))
    }

    @Test
    @DisplayName("ONB-001 rev. 3: a Checkout Directory moved to another repository; the root entry does not inherit a name now in use")
    fun `checkout directory moved to another repository`() {
        val config = baseRow()
        config.vcsEntries.add(
            VcsSettingsEntryEntity(componentConfiguration = config, name = "ui", vcsPath = REPO_A, sortOrder = 0, checkoutDirectory = "ui"),
        )
        write(config, VcsEntryRequest(vcsPath = REPO_A), VcsEntryRequest(vcsPath = REPO_B, checkoutDirectory = "UI"))

        assertEquals(listOf("main", "UI"), names(config))
    }

    @Test
    @DisplayName("ONB-001 rev. 3: two entries reduced to one; an entry moved to the root keeps its own repository's name")
    fun `two entries reduced to one`() {
        val kept = stored(baseRow(), "alpha", "beta")
        write(kept, VcsEntryRequest(vcsPath = kept.vcsEntries[0].vcsPath))
        assertEquals(listOf("alpha"), names(kept))

        val moved = stored(baseRow(), "alpha", "beta")
        write(moved, VcsEntryRequest(name = "alpha", vcsPath = moved.vcsEntries[1].vcsPath, checkoutDirectory = null))
        assertEquals(listOf("beta"), names(moved))
        assertNull(moved.vcsEntries.single().checkoutDirectory)
    }

    @Test
    @DisplayName("ONB-001: an emptied row is accepted and its next single entry is named main")
    fun `emptied row refilled`() {
        val config = stored(baseRow(), "alpha", "beta")
        write(config)
        assertTrue(config.vcsEntries.isEmpty())

        write(config, VcsEntryRequest(name = "alpha", vcsPath = REPO_A))
        assertEquals(listOf("main"), names(config))
    }

    @Test
    @DisplayName("ONB-001: an imported multi-root row saves unchanged")
    fun `imported row saves unchanged`() {
        val config = stored(baseRow(), "alpha", "beta")
        write(config, *echo(config))

        assertEquals(listOf("alpha", "beta"), names(config))
        assertEquals(listOf(null, "beta"), config.vcsEntries.map { it.checkoutDirectory })
    }

    companion object {
        private const val REPO_A = "ssh://git@example.test/proj/repo-a.git"
        private const val REPO_B = "ssh://git@example.test/proj/repo-b.git"
        private const val REPO_C = "ssh://git@example.test/proj/repo-c.git"
    }
}
