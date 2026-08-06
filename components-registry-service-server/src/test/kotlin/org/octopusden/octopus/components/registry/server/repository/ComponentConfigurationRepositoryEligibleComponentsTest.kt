package org.octopusden.octopus.components.registry.server.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
@Tag("integration")
class ComponentConfigurationRepositoryEligibleComponentsTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

    @Test
    @DisplayName("only non-archived MAVEN/GRADLE components are eligible")
    fun `findNonArchivedMavenOrGradleComponentKeys returns only non-archived Maven or Gradle components`() {
        componentRepository.saveAll(
            listOf(
                component("maven-comp", buildSystem = "MAVEN"),
                component("gradle-comp", buildSystem = "GRADLE"),
                component("golang-comp", buildSystem = "GOLANG"),
                component("archived-maven-comp", buildSystem = "MAVEN", archived = true),
            ),
        )

        val eligible = configurationRepository.findNonArchivedMavenOrGradleComponentKeys()

        assertEquals(setOf("maven-comp", "gradle-comp"), eligible.toSet())
    }

    private fun component(
        key: String,
        buildSystem: String,
        archived: Boolean = false,
    ): ComponentEntity {
        val comp = ComponentEntity(componentKey = key)
        comp.archived = archived
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                isSyntheticBase = false,
                buildSystem = buildSystem,
                jiraProjectKey = null,
                deprecated = false,
            )
        comp.configurations.add(base)
        return comp
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
