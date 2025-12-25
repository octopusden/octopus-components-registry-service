package org.octopusden.octopus.components.registry.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.api.beans.GitVersionControlSystemBean
import org.octopusden.octopus.components.registry.api.enums.BuildSystemType
import org.octopusden.octopus.components.registry.api.vcs.VersionControlSystem
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

/**
 * Test [org.octopusden.octopus.components.registry.server.controller.ComponentControllerV3] controller.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(SpringExtension::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class]
)
@ActiveProfiles("v3", "test")
@ResourceLock(value = "SYSTEM_PROPERTIES")
class ComponentRegistryServiceClientV3Test {
    init {
        BaseComponentsRegistryServiceTest.configureSpringAppTestDataDir()
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var componentsRegistryClient: ComponentsRegistryServiceClient

    @BeforeAll
    internal fun startupServer() {
        componentsRegistryClient = ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String {
                    return "http://localhost:$port"
                }
            }
        )
    }

    /**
     * Test [org.octopusden.octopus.components.registry.server.controller.ComponentControllerV3.getAllComponents].
     */
    @ParameterizedTest
    @MethodSource("componentsData")
    @DisplayName("Test retrieving all components")
    fun testGetComponents(
        componentKey: String,
        versionRange: String,
        buildSystemType: BuildSystemType,
        versionControlSystem: VersionControlSystem
    ) {
        val component = componentsRegistryClient.getComponents().find { it.component.id == componentKey }
        assertThat(component).isNotNull
        assertThat(component?.variants?.get(versionRange)).isNotNull
        assertThat(component!!.variants[versionRange]?.build?.buildSystem?.type).isEqualTo(buildSystemType)
        assertThat(component.variants[versionRange]?.vcs).isEqualTo(versionControlSystem)
    }

    /**
     * Test [org.octopusden.octopus.components.registry.server.controller.ComponentControllerV3.getAllComponents] build.dependencies.autoUpdate attribute deserialization.
     */
    @ParameterizedTest
    @MethodSource("componentsAutoUpdate")
    @DisplayName("Test component build->dependencies->autoUpdate attribute deserialization")
    fun testComponentAutoUpdateDeserialization(
        componentKey: String,
        expectedAutoUpdateValue: Boolean?
    ) {
        assertThat(componentsRegistryClient.getComponents().find { it.component.id == componentKey }!!.variants.values.first().build?.dependencies?.autoUpdate)?.isEqualTo(expectedAutoUpdateValue)
    }

    @ParameterizedTest(name = "Downloading copyright file of {0} component")
    @MethodSource("componentsCopyright")
    @DisplayName("Test successful downloading copyright file specified in component")
    fun testCopyrightSuccessfullyDownloaded(
        componentKey: String,
        expectedCopyrightText: String,
    ) {
        val response = componentsRegistryClient.getCopyrightByComponent(componentKey)
        assertThat(response.status()).isEqualTo(200)
        val body = response.body()
        assertThat(body).isNotNull
        val actualCopyrightText = body.asInputStream().use {
            it.reader(StandardCharsets.UTF_8).readText()
        }
        assertThat(actualCopyrightText).isEqualTo(expectedCopyrightText)
    }

    companion object {
        @JvmStatic
        fun componentsData(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "gradle-staging-plugin",
                "[1.200, 2)",
                BuildSystemType.GRADLE,
                GitVersionControlSystemBean(
                    "ssh://git@github.com:octopusden/archive/gradle-staging-plugin.git",
                    "\$module-\$version",
                    "master"
                )
            ),
            Arguments.of(
                "gradle-staging-plugin",
                "(,1.200),[2,)",
                BuildSystemType.GRADLE,
                GitVersionControlSystemBean(
                    "ssh://git@github.com:octopusden/octopus-rm-gradle-plugin.git",
                    "release-management-gradle-plugin-\$version",
                    "master"
                )
            )
        )

        @JvmStatic
        fun componentsAutoUpdate(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "gradle-staging-plugin",
                null
            ),
            Arguments.of(
                "SMComponent",
                true
            )
        )

        @JvmStatic
        fun componentsCopyright(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "gradle-staging-plugin",
                "CompanyName1 copyright text"
            ),
            Arguments.of(
                "SMComponent",
                "CompanyName2 another copyright text"
            )
        )
    }
}
