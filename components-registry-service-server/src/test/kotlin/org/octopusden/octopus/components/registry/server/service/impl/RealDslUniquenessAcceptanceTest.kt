package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionDockerImageRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import java.net.URI
import java.nio.file.Paths
import java.util.EnumMap

/**
 * ACCEPTANCE gate against the REAL production components-registry DSL: the §6.0
 * migration uniqueness pre-pass must report ZERO violations on data that passes
 * the daily legacy validation ([2.0] Validate Production Data) — anything it
 * flags there is, by definition, a false positive of OUR semantics, and it
 * bricks the candidate boot in the [1.7]/[1.8] compat stands (observed
 * 2026-06-12 on build 3926: 4 phantom GAV collisions from legacy CSV-group /
 * wildcard-artifact matching + 1 phantom jira claim from raw-row bucketing).
 *
 * Skipped unless REAL_CR_DSL_DIR points at a checkout's groovy dir (the one
 * containing Aggregator.groovy), e.g.:
 *
 *   REAL_CR_DSL_DIR=$HOME/components-registry/src/main/resources \
 *     ./gradlew :components-registry-service-server:test --tests "*.RealDslUniquenessAcceptanceTest"
 *
 * Loader parameters are read from the checkout's own gradle.properties (the
 * [2.0] job's configuration source): supportedGroupIds/systems, versionNames,
 * productTypes — nothing environment-specific is hardcoded here.
 */
@EnabledIfEnvironmentVariable(named = "REAL_CR_DSL_DIR", matches = ".+")
class RealDslUniquenessAcceptanceTest {

    private fun clearScriptRunnerProductTypes() {
        val runnerClass =
            Class.forName("org.octopusden.octopus.components.registry.dsl.script.ComponentsRegistryScriptRunner")
        val instance = runnerClass.getDeclaredField("INSTANCE").get(null)
        val mapField = runnerClass.getDeclaredField("productTypeMap")
        mapField.isAccessible = true
        (mapField.get(instance) as MutableMap<*, *>).clear()
    }

    @Test
    @DisplayName("§6.0 uniqueness pre-pass reports zero violations on the real production DSL")
    fun realDsl_zeroUniquenessViolations() {
        val dslDir = Paths.get(System.getenv("REAL_CR_DSL_DIR")).toAbsolutePath().normalize()
        // Loader parameters come from the checkout's OWN gradle.properties (the
        // [2.0] job's configuration source) — no environment-specific values are
        // hardcoded here, and the test follows whatever the registry repo declares.
        val props = java.util.Properties()
        val propsFile = generateSequence(dslDir) { it.parent }
            .map { it.resolve("gradle.properties") }
            .firstOrNull { java.nio.file.Files.exists(it) }
            ?: throw AssertionError("gradle.properties not found above $dslDir")
        java.nio.file.Files.newInputStream(propsFile).use { props.load(it) }
        fun required(key: String): String =
            props.getProperty(key)?.takeIf { it.isNotBlank() }
                ?: throw AssertionError("$key missing in $propsFile")

        val versionNames = VersionNames(
            required("components-registry.version-name.service-branch"),
            required("components-registry.version-name.service"),
            required("components-registry.version-name.minor"),
        )
        val productTypes =
            EnumMap<ProductTypes, String>(ProductTypes::class.java).apply {
                put(ProductTypes.PT_C, required("components-registry.product-type.c"))
                put(ProductTypes.PT_K, required("components-registry.product-type.k"))
                put(ProductTypes.PT_D, required("components-registry.product-type.d"))
                put(ProductTypes.PT_D_DB, required("components-registry.product-type.ddb"))
            }
        val loader =
            EscrowConfigurationLoader(
                ConfigLoader(
                    ComponentRegistryInfo.createFromURL(
                        URI("file://$dslDir/Aggregator.groovy").toURL(),
                    ),
                    versionNames,
                    productTypes,
                ),
                required("components-registry.supportedGroupIds").split(',').map { it.trim() },
                required("components-registry.supportedSystems").split(',').map { it.trim() },
                versionNames,
                null,
            )

        // ComponentsRegistryScriptRunner is a JVM-global singleton whose productTypeMap
        // is populated ONCE (`if (isEmpty)`): in the shared :test JVM, earlier Spring
        // tests seed it with the fixture naming (PT_C…), and the prod DSL's own
        // product-type names then fail decodeParameters. Clear it around the load so
        // this test sees the prod mapping and later users re-init with their own.
        clearScriptRunnerProductTypes()
        val fullConfig =
            try {
                // Same lenient load migrate() uses (semantic warnings tolerated; the
                // uniqueness pre-pass is the gate under test).
                loader.loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap())
            } finally {
                clearScriptRunnerProductTypes()
            }
        assertTrue(fullConfig.escrowModules.isNotEmpty(), "real DSL loaded no modules — wrong REAL_CR_DSL_DIR?")

        // ImportServiceImpl with all-mock repositories: Mockito's default answers
        // return EMPTY collections, so the DB-side halves of every invariant are
        // empty and the pre-pass exercises pure DSL-vs-DSL semantics — exactly the
        // empty-DB state of a compat-stand candidate boot.
        val service = ImportServiceImpl(
            gitResolver = mock(ComponentRegistryResolverImpl::class.java),
            dbResolver = mock(DatabaseComponentRegistryResolver::class.java),
            componentSourceRepository = mock(ComponentSourceRepository::class.java),
            sourceRegistry = mock(ComponentSourceRegistry::class.java),
            configurationLoader = mock(org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader::class.java),
            configSyncService = mock(ConfigSyncService::class.java),
            componentRepository = mock(ComponentRepository::class.java),
            configurationRepository = mock(ComponentConfigurationRepository::class.java),
            componentGroupRepository = mock(ComponentGroupRepository::class.java),
            systemRepository = mock(SystemRepository::class.java),
            toolRepository = mock(ToolRepository::class.java),
            labelRepository = mock(LabelRepository::class.java),
            componentLabelRepository = mock(ComponentLabelRepository::class.java),
            componentRequiredToolRepository = mock(ComponentRequiredToolRepository::class.java),
            componentBuildToolBeanRepository = mock(ComponentBuildToolBeanRepository::class.java),
            mavenArtifactRepository = mock(DistributionMavenArtifactRepository::class.java),
            dockerImageRepository = mock(DistributionDockerImageRepository::class.java),
            versionRangeFactory = VersionRangeFactory(versionNames),
        )

        val detect = ImportServiceImpl::class.java
            .getDeclaredMethod("detectUniquenessViolations", Map::class.java)
        detect.isAccessible = true
        try {
            detect.invoke(service, fullConfig.escrowModules)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw AssertionError(
                "uniqueness pre-pass flagged the legacy-valid production DSL:\n${e.cause?.message}",
                e.cause,
            )
        }
    }
}
