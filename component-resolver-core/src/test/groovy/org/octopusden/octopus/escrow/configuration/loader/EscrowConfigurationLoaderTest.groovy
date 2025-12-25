package org.octopusden.octopus.escrow.configuration.loader

import groovy.util.logging.Slf4j
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.versioning.VersionRange
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.api.beans.GitVersionControlSystemBean
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.api.enums.BuildSystemType
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.resolvers.ModuleByArtifactResolver
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.releng.versions.NumericVersionFactory

import java.nio.file.Paths
import java.util.stream.Stream

import static org.assertj.core.api.AssertionsForClassTypes.assertThat
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.octopusden.octopus.escrow.TestConfigUtils.COPYRIGHT_PATH
import static org.octopusden.octopus.escrow.TestConfigUtils.PRODUCT_TYPES
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_NAMES
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_RANGE_FACTORY

@Slf4j
class EscrowConfigurationLoaderTest {

    EscrowConfiguration escrowConfiguration
    private static EscrowConfigurationLoader escrowConfigurationLoader

    @BeforeAll
    static void init() {
        def aggregatorPath = Paths.get(EscrowConfigurationLoaderTest.class.getResource("/production/Aggregator.groovy").toURI())
        escrowConfigurationLoader = new EscrowConfigurationLoader(
                new ConfigLoader(
                        ComponentRegistryInfo.createFromFileSystem(
                                aggregatorPath.getParent().toString(),
                                aggregatorPath.getFileName().toString()),
                        VERSION_NAMES,
                        PRODUCT_TYPES
                ),
                SUPPORTED_GROUP_IDS,
                SUPPORTED_SYSTEMS,
                VERSION_NAMES,
                COPYRIGHT_PATH
        )
    }

    @BeforeEach
    void setUp() {
        escrowConfiguration = escrowConfigurationLoader.loadFullConfiguration(Collections.emptyMap())
    }

    /**
     * Versioned component data to verify {@link org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig#toVersionedComponent()}.
     *
     */
    static Stream<Arguments> versionedComponentData() {
        Stream.of(
                Arguments.of("component_commons", "2.0.0", BuildSystemType.GRADLE, new GitVersionControlSystemBean("git@gitlab:platform/component_commons.git", 'component_commons-$version', 'master')),
                Arguments.of("docs", "1.6.1", BuildSystemType.MAVEN, new GitVersionControlSystemBean("ssh://git@github.com/octopusden/server/docs", 'docs-$version', '1.6')),
                Arguments.of("gradle-staging-plugin", "1.200.1", BuildSystemType.GRADLE, new GitVersionControlSystemBean("ssh://git@github.com:octopusden/archive/gradle-staging-plugin.git", '$module-$version', 'master')),
                Arguments.of("gradle-staging-plugin", "2.1", BuildSystemType.GRADLE, new GitVersionControlSystemBean("ssh://git@github.com:octopusden/octopus-rm-gradle-plugin.git", 'release-management-gradle-plugin-$version', 'master'))
        )
    }

    /**
     * Test 'dependencies' DSL configuration.
     */
    @ParameterizedTest
    @MethodSource("versionedComponentData")
    @DisplayName("Test transforming to versioned component")
    void testToVersionComponent(String componentName, String componentVersion, BuildSystemType buildSystemType, GitVersionControlSystemBean gitVersionControlSystemBean) {
        def escrowModule = getEscrowConfiguration().escrowModules.get(componentName)
        def vrf =  VERSION_RANGE_FACTORY
        def numericVersionFactory = new NumericVersionFactory(VERSION_NAMES)
        def moduleConfiguration = Objects.requireNonNull(escrowModule.getModuleConfigurations().find {module -> vrf.create(module.versionRangeString).containsVersion(numericVersionFactory.create(componentVersion)) })
        def versionedComponent = moduleConfiguration.toVersionedComponent()
        assertThat(versionedComponent.build.buildSystem.type).isEqualTo(buildSystemType)
        assertThat(versionedComponent.vcs).isEqualTo(gitVersionControlSystemBean)
    }

    /**
     * Dependencies DSL source data.
     */
    static Stream<Arguments> dependenciesData() {
        Stream.of(
                Arguments.of("component_commons", "2.0.0", true),
                Arguments.of("app", "1.7.1345", true),
                Arguments.of("server-distribution", "200", true),
                Arguments.of("ansible", "1.7.1347", false),
                Arguments.of("installer", "1.5.1", true),
                Arguments.of("server", "1.7.1", false), //Overridden in MiddlewareComponents.kts
                Arguments.of("db-api", "99999", false),
                Arguments.of("db-p", "1.0", false),
                Arguments.of("ptkdb_api", "03.51.29.15", true),
                Arguments.of("pt_k_db_api", "03.51.29.15", false)
        )
    }

    /**
     * Test 'dependencies' DSL configuration.
     */
    @ParameterizedTest
    @MethodSource("dependenciesData")
    @DisplayName("Test dependencies DSL")
    void testDependenciesDsl(String componentName, String componentVersion, boolean autoUpdate) {
        def escrowModule = getEscrowConfiguration().escrowModules.get(componentName)
        def vrf = VERSION_RANGE_FACTORY
        def numericVersionFactory = new NumericVersionFactory(VERSION_NAMES)
        def moduleConfiguration = Objects.requireNonNull(escrowModule.getModuleConfigurations().find {module -> vrf.create(module.versionRangeString).containsVersion(numericVersionFactory.create(componentVersion)) })
        assertThat(moduleConfiguration).isNotNull().extracting {it.buildConfiguration?.dependencies?.autoUpdate ?: false }.isEqualTo(autoUpdate)
    }

    @Test
    void testResolvingKDbApiArtifact() {
        def resolver = new ModuleByArtifactResolver(getEscrowConfiguration())
        ComponentVersion componentVersion = resolver.resolveComponentByArtifact(new DefaultArtifact("org.octopusden.octopus.ptkmodel2", "ptkdb_model", VersionRange.createFromVersion("03.51.29.15"), "compile", "jar", "", null))
        assertEquals("03.51.29.15", componentVersion.getVersion())
        componentVersion = resolver.resolveComponentByArtifact(new DefaultArtifact("org.octopusden.octopus.ptkmodel2", "ptkdb_model", VersionRange.createFromVersion("3.51.29-0015"), "compile", "jar", "", null))
        assertEquals("3.51.29-0015", componentVersion.getVersion())
    }

    @Test
    void testBuildToolsForNonVersioned() {
        EscrowModule escrowModule = getEscrowConfiguration().escrowModules.get("monitoring")
        def rootModule = escrowModule.moduleConfigurations.find { it.versionRangeString == EscrowConfigurationLoader.ALL_VERSIONS}
        assertNotNull(rootModule)
        def oracle = new OracleDatabaseToolBean()
        oracle.version = "11.2"
        assertTrue(rootModule.buildConfiguration.buildTools.contains(oracle))
    }

    @Test
    void testNotSetBuildTools() {
        EscrowModule escrowModule = getEscrowConfiguration().escrowModules.get("legacy")
        escrowModule.moduleConfigurations.forEach {
            assertTrue(it.buildConfiguration.buildTools.isEmpty())
        }
    }

    @Test
    void testBuildToolsForVersionRange() {
        EscrowModule escrowModule = getEscrowConfiguration().escrowModules.get("app")
        def rootModule = escrowModule.moduleConfigurations.find { it.versionRangeString == EscrowConfigurationLoader.ALL_VERSIONS}
        assertNull(rootModule)
        escrowModule.moduleConfigurations.forEach { moduleConfiguration ->
            def oracle = new OracleDatabaseToolBean()
            if (moduleConfiguration.versionRangeString == "[1.7,2)") {
                oracle.version = "12"
            } else {
                oracle.version = "11.2"
            }
            assertTrue(moduleConfiguration.buildConfiguration.buildTools.contains(oracle), "${moduleConfiguration.buildConfiguration.buildTools} 1sn't contain $oracle escrowModule=$escrowModule")
        }
    }

    @Test
    void testEscrowForVersionedComponent() {
        getEscrowConfiguration().escrowModules.get("app").moduleConfigurations.forEach { moduleConfiguration ->
            if (moduleConfiguration.versionRangeString == "[1.7,2)") {
                assertTrue(moduleConfiguration.escrow.gradle.includeTestConfigurations)
                assertEquals("build", moduleConfiguration.escrow.buildTask)
            } else {
                log.info("moduleConfiguration=$moduleConfiguration")
                assertNull(moduleConfiguration.escrow.buildTask, "${moduleConfiguration.escrow.buildTask} should be empty")
            }
        }
    }

    @Test
    void testKProduct() {
        getEscrowConfiguration().escrowModules.get("monitoring").moduleConfigurations.forEach { moduleConfiguration ->
            def kProduct = new PTKProductToolBean()
            kProduct.version = "03.49"
            log.info("moduleConfiguration=$moduleConfiguration")
            assertTrue(moduleConfiguration.buildConfiguration.buildTools.contains(kProduct), "${moduleConfiguration.buildConfiguration.buildTools} doesn't contain $kProduct moduleConfiguration=$moduleConfiguration")
        }
    }

    @Test
    void testProductForVersionedComponent() {
        getEscrowConfiguration().escrowModules.get("app").moduleConfigurations.forEach { moduleConfiguration ->
            def product = new PTKProductToolBean()
            if (moduleConfiguration.versionRangeString == "[1.7,2)") {
                product.version = "03.50"
            } else {
                product.version = "03.49"
            }
            assertTrue(moduleConfiguration.buildConfiguration.buildTools.contains(product))
        }
    }

    @Test
    void testEmptyProduct() {
        escrowConfiguration.escrowModules.get("component_commons").moduleConfigurations.forEach { moduleConfiguration ->
            def product = new PTKProductToolBean()
            assertTrue(moduleConfiguration.buildConfiguration.buildTools.contains(product), "$moduleConfiguration.buildConfiguration.buildTools doesn't contain $product")
        }
    }

    @Test
    void testVcsAutoDetection() {
        assertEquals(RepositoryType.GIT, EscrowConfigurationLoader.detectRepositoryType("ssh://git@git.someorganisation.com/releng/wl-tool.git", RepositoryType.CVS))
        assertEquals(RepositoryType.GIT, EscrowConfigurationLoader.detectRepositoryType("ssh://git@zenit-champion.ru/zenit/kerzhakoff.git", RepositoryType.MERCURIAL))

        getEscrowConfiguration().escrowModules.get("component_commons").moduleConfigurations.forEach { moduleConfiguration ->
            assertTrue(moduleConfiguration.vcsSettings.singleVCSRoot.repositoryType == RepositoryType.GIT)
        }
        getEscrowConfiguration().escrowModules.get("tool").moduleConfigurations.forEach { moduleConfiguration ->
            assertTrue(moduleConfiguration.vcsSettings.singleVCSRoot.repositoryType == RepositoryType.GIT)
        }
        getEscrowConfiguration().escrowModules.get("component_proxy").moduleConfigurations.forEach { moduleConfiguration ->
            assertTrue(moduleConfiguration.vcsSettings.singleVCSRoot.repositoryType == RepositoryType.GIT)
        }
    }
}
