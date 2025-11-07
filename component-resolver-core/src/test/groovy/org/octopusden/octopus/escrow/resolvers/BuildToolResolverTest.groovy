package org.octopusden.octopus.escrow.resolvers

import groovy.transform.TypeChecked
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean
import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.api.distribution.entities.MavenArtifactDistributionEntity
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.releng.dto.ComponentVersion

import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.octopusden.octopus.escrow.TestConfigUtils.COPYRIGHT_PATH
import static org.octopusden.octopus.escrow.TestConfigUtils.PRODUCT_TYPES
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_NAMES

@TypeChecked
class BuildToolResolverTest {

    private IBuildToolsResolver buildToolsResolver
    private static EscrowConfigurationLoader escrowConfigurationLoader

    @BeforeAll
    static void initBeforeClass() {
        escrowConfigurationLoader = initEscrowConfigurationLoader()
    }

    @BeforeEach
    void setUp() {
        buildToolsResolver = initBuildToolResolver(escrowConfigurationLoader)
    }

    private static EscrowConfigurationLoader initEscrowConfigurationLoader() {
        def aggregatorPath = Paths.get(EscrowConfigurationLoaderTest.class.getResource("/production/Aggregator.groovy").toURI())
        EscrowConfigurationLoader escrowConfigurationLoader = new EscrowConfigurationLoader(
                new ConfigLoader(
                        ComponentRegistryInfo.createFromFileSystem(
                                aggregatorPath.getParent().toString(),
                                aggregatorPath.getFileName().toString()
                        ),
                        VERSION_NAMES,
                        PRODUCT_TYPES
                ),
                SUPPORTED_GROUP_IDS,
                SUPPORTED_SYSTEMS,
                VERSION_NAMES,
                COPYRIGHT_PATH
        )
        escrowConfigurationLoader
    }

    private static BuildToolsResolver initBuildToolResolver(EscrowConfigurationLoader escrowConfigurationLoader) {
        new BuildToolsResolver(escrowConfigurationLoader.loadFullConfiguration(Collections.emptyMap()))
    }

    @Test
    void testProductComponent() {
        assertEquals("pt_k_db", buildToolsResolver.getProductMappedComponent(ProductTypes.PT_K).get())
        assertFalse(buildToolsResolver.getProductMappedComponent(ProductTypes.PT_C).isPresent())
        def a  = buildToolsResolver.getComponentProductMapping()
        assertEquals(ProductTypes.PT_K, buildToolsResolver.getComponentProductMapping().get("pt_k_db"))
    }

    @Test
    void testGetBuildTool() {
        def tools = buildToolsResolver.getComponentBuildTools(ComponentVersion.create("app", "1.6.1"))
        def oracle = new OracleDatabaseToolBean()
        oracle.version = "11.2"
        assertTrue(tools.contains(oracle), "$tools doesn't contain $oracle")
        def ptk = new PTKProductToolBean()
        ptk.version = "03.49"
        assertTrue(tools.contains(ptk))
    }

    @Test
    void testGetOverriddenBuildTool() {
        def tools = buildToolsResolver.getComponentBuildTools(ComponentVersion.create("app", "1.6.1"))
        def ptk = new PTKProductToolBean()
        ptk.version = "03.49"
        assertTrue(tools.contains(ptk), "$tools doesn't contain $ptk")

        tools = buildToolsResolver.getComponentBuildTools(ComponentVersion.create("app", "1.6.1"), "03.50.30.45")
        ptk.version = "03.50.30.45"
        assertTrue(tools.contains(ptk))

        tools = buildToolsResolver.getComponentBuildTools(ComponentVersion.create("app", "1.6.1"), "03.51.30.12")
        ptk.version = "03.51.30.12"
        assertTrue(tools.contains(ptk))

        tools = buildToolsResolver.getComponentBuildTools(ComponentVersion.create("app", "1.6.1"))
        ptk.version = "03.49"
        assertTrue(tools.contains(ptk))
    }

    @Test
    void testGetBuildToolWhenNotSet() {
        def tools = buildToolsResolver.getComponentBuildTools(ComponentVersion.create("docs", "1.6.1"))
        assertTrue(tools.empty)
    }

    @Test
    void testGetBuildToolFromGroovySet() {
        def tools = buildToolsResolver.getComponentBuildTools(ComponentVersion.create("Component", "1.1.158"))
        def toolBean = new PTCProductToolBean()
        toolBean.version = "03.42"
        assertTrue(tools.contains(toolBean))
    }

    @Test
    void testGetBuildToolFromGroovyAndDsl() {
        def tools = buildToolsResolver.getComponentBuildTools(ComponentVersion.create("Component", "1.1.211"))
        def toolBean = new PTCProductToolBean()
        toolBean.version = "03.42"
        assertTrue(tools.contains(toolBean))

        def ptk = new PTKProductToolBean()
        assertTrue(tools.contains(ptk))

        tools = buildToolsResolver.getComponentBuildTools(ComponentVersion.create("Component", "1.1.211"), "03.50.30.45")
        toolBean.version = "03.50.30.45"
        assertTrue(tools.contains(toolBean))
        ptk.version = "03.50.30.45"
        assertTrue(tools.contains(ptk))
    }

    @Test
    void testGetDistributionEntities() {
        def entities = buildToolsResolver.getDistributionEntities(ComponentVersion.create("app", "1.7.1"))
        assertFalse(entities.empty)
        entities.forEach {
            assertTrue(it instanceof MavenArtifactDistributionEntity)
            assertEquals('org.octopusden.octopus.distribution.server', (it as MavenArtifactDistributionEntity).groupId)
            assertEquals('app', (it as MavenArtifactDistributionEntity).artifactId)
            assertEquals('zip', (it as MavenArtifactDistributionEntity).extension.orElseThrow {new IllegalStateException() })
        }
        assertNotNull(entities.find {'windows-x64-nojdk' == (it as MavenArtifactDistributionEntity).classifier.orElseThrow{new IllegalStateException() }})
    }

    @Test
    void testGetDistributionEntitiesWhenNotSet() {
        assertTrue(buildToolsResolver.getDistributionEntities(ComponentVersion.create("monitoring", "1.0.1984")).empty)
    }

    @Test
    void testIgnoreRequiring() {
        def component = ComponentVersion.create("Component", "1.1.464-10860")

        def toolBean = new PTCProductToolBean()
        toolBean.version = "03.42"

        assertTrue(buildToolsResolver.getComponentBuildTools(component).contains(toolBean))
        assertFalse(buildToolsResolver.getComponentBuildTools(component, null, true).contains(toolBean))
    }
}
