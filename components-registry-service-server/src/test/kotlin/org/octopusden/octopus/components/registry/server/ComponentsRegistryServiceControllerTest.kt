package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.components.registry.core.dto.ComponentV2
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.DocDTO
import org.octopusden.octopus.components.registry.core.dto.EscrowDTO
import org.octopusden.octopus.components.registry.core.dto.EscrowGenerationMode
import org.octopusden.octopus.components.registry.core.dto.SecurityGroupsDTO
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Date

@AutoConfigureMockMvc
@ExtendWith(SpringExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test")
class ComponentsRegistryServiceControllerTest : MockMvcRegistryTestSupport() {
    private val docker = "test/versions-api"
    private val gav = "org.octopusden.octopus.test:versions-api:jar"

    @Test
    fun testPing() {
        val response =
            mvc
                .perform(MockMvcRequestBuilders.get("/rest/api/2/components-registry/service/ping"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        Assertions.assertEquals("Pong", response)
    }

    @Test
    fun testUpdateCacheStatus() {
        val statusOnStart = getServiceStatus()

        val timeBeforeUpdate = Date()

        Assertions.assertTrue(timeBeforeUpdate.after(statusOnStart.cacheUpdatedAt))

        mvc
            .perform(MockMvcRequestBuilders.put("/rest/api/2/components-registry/service/updateCache"))
            .andExpect(status().isOk)

        val statusOnUpdateCacheStatusDTO = getServiceStatus()
        Assertions.assertTrue(timeBeforeUpdate.before(statusOnUpdateCacheStatusDTO.cacheUpdatedAt))
    }

    @Test
    fun testGetComponents() {
        val components =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/api/1/components")
                        .param("expand", "true")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .toObject(Components::class.java)

        val expectedComponent = ComponentV1("TESTONE", "Test ONE display name", "adzuba")
        expectedComponent.distribution =
            DistributionDTO(
                false,
                false,
                gav,
                null,
                null,
                SecurityGroupsDTO(
                    listOf("vfiler1-default#group"),
                ),
                docker,
            )
        expectedComponent.escrow =
            EscrowDTO(
                buildTask = "clean build -x test",
                providedDependencies = listOf("test:test:1.1"),
                additionalSources =
                    listOf(
                        "spa/.gradle",
                        "spa/node_modules",
                    ),
                isReusable = false,
                generation = EscrowGenerationMode.UNSUPPORTED,
            )
        expectedComponent.releaseManager = "user"
        expectedComponent.securityChampion = "user"
        expectedComponent.system = setOf("ALFA", "CLASSIC")
        expectedComponent.clientCode = "CLIENT_CODE"
        expectedComponent.releasesInDefaultBranch = false
        expectedComponent.solution = true
        expectedComponent.copyright = "companyName1"
        expectedComponent.labels = setOf("Label2")

        Assertions.assertEquals(56, components.components.size)
        Assertions.assertTrue(expectedComponent in components.components) {
            components.components.toString()
        }
    }

    @Test
    fun testGetComponentsFilteredByVcsPath() {
        getAndCheckComponents(
            "ssh://hg@mercurial/technical",
            null,
            setOf("TECHNICAL_COMPONENT", "SUB_COMPONENT_ONE", "SUB_COMPONENT_TWO"),
        )
        getAndCheckComponents(
            "ssh://hg@mercurial/technical",
            BuildSystem.MAVEN,
            setOf("SUB_COMPONENT_TWO"),
        )
        getAndCheckComponents(
            null,
            BuildSystem.MAVEN,
            setOf("SUB_COMPONENT_TWO", "TEST_COMPONENT", "TEST_COMPONENT_VERSIONED_ARTIFACT", "TEST-VERSION"),
        )
    }

    @Test
    fun testGetNonExistedComponent() {
        val exception =
            Assertions.assertThrows(AssertionError::class.java) {
                getDetailedComponent("NOTEXIST", "1")
            }
        Assertions.assertEquals("Status expected:<200> but was:<404>", exception.message)
    }

    @Test
    fun testGetComponentV2() {
        val actualComponent =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/api/2/components/TESTONE")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TESTONE", "Test ONE display name", "adzuba")
        expectedComponent.distribution =
            DistributionDTO(
                false,
                false,
                gav,
                null,
                null,
                SecurityGroupsDTO(listOf("vfiler1-default#group")),
                docker,
            )
        expectedComponent.escrow =
            EscrowDTO(
                buildTask = "clean build -x test",
                providedDependencies = listOf("test:test:1.1"),
                additionalSources =
                    listOf(
                        "spa/.gradle",
                        "spa/node_modules",
                    ),
                isReusable = false,
                generation = EscrowGenerationMode.UNSUPPORTED,
            )

        expectedComponent.releaseManager = "user"
        expectedComponent.securityChampion = "user"
        expectedComponent.system = setOf("ALFA", "CLASSIC")
        expectedComponent.clientCode = "CLIENT_CODE"
        expectedComponent.releasesInDefaultBranch = false
        expectedComponent.solution = true
        expectedComponent.copyright = "companyName1"
        expectedComponent.labels = setOf("Label2")

        Assertions.assertEquals(expectedComponent.escrow, actualComponent.escrow, "Escrow do not match")
        Assertions.assertEquals(expectedComponent, actualComponent)
    }

    /**
     * This test is similar to [testGetComponentV2] and exists to ensure that escrow block is correctly
     * deserialized from the response.
     */
    @Test
    fun testGetComponentV2WithEscrowBlock() {
        val actualComponent =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/api/2/components/TEST_COMPONENT_WITH_ESCROW")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_ESCROW", null, "user9")
        expectedComponent.escrow =
            EscrowDTO(
                buildTask = null,
                providedDependencies = listOf(),
                additionalSources = listOf(),
                isReusable = true,
                generation = EscrowGenerationMode.MANUAL,
                diskSpaceRequirement = null,
            )

        Assertions.assertEquals(expectedComponent.escrow, actualComponent.escrow, "Escrow do not match")
    }

    @Test
    fun testGetComponentDoc() {
        val actualComponent =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/api/2/components/TEST_COMPONENT_WITH_DOC")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_DOC", "Test Component with Doc", "user9")
        expectedComponent.doc =
            DocDTO(
                "TEST_COMPONENT_DOC",
                "1.2",
            )
        Assertions.assertEquals(
            expectedComponent.doc,
            actualComponent.doc,
            "Components do not match",
        )
    }

    @Test
    fun testGetComponentDocWithVersionRange() {
        val actualComponent =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/api/2/components/TEST_COMPONENT_WITH_DOC_AND_VERSIONS/versions/1.3")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_DOC_AND_VERSIONS", "Test Component with Doc", "user9")
        expectedComponent.doc =
            DocDTO(
                "TEST_COMPONENT_DOC",
                "1.2",
            )
        Assertions.assertEquals(
            expectedComponent.doc,
            actualComponent.doc,
            "Doc do not match",
        )
    }

    /**
     * Verifies that Doc configured at component level is correctly overridden by version-range-specific Doc.
     * TEST_COMPONENT_WITH_DOC_AND_VERSIONS has component-level doc (TEST_COMPONENT_DOC:1.2) and
     * version range [1.0,1.3) overrides it with doc_mycomponent:1.3.
     * For version 1.2.5 (within [1.0,1.3)) the API must return the overridden doc, not the component-level one.
     */
    @Test
    fun testGetComponentVersionDocOverriddenAtVersionLevel() {
        val actualComponent =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/api/2/components/TEST_COMPONENT_WITH_DOC_AND_VERSIONS/versions/1.2.5")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_DOC_AND_VERSIONS", "Test Component with Doc", "user9")
        expectedComponent.doc =
            DocDTO(
                "doc_mycomponent",
                "1.3",
            )
        Assertions.assertEquals(
            expectedComponent.doc,
            actualComponent.doc,
            "Doc overridden at version level [1.0,1.3) should be doc_mycomponent:1.3, not component-level TEST_COMPONENT_DOC:1.2",
        )
    }

    @Test
    fun testGetComponentVersionEscrow() {
        val actualComponent =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/api/2/components/TEST_COMPONENT_WITH_ESCROW/versions/1.0.0")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_ESCROW", null, "user9")
        expectedComponent.escrow =
            EscrowDTO(
                buildTask = null,
                providedDependencies = listOf(),
                additionalSources = listOf(),
                isReusable = true,
                generation = EscrowGenerationMode.MANUAL,
            )

        Assertions.assertEquals(
            expectedComponent.escrow,
            actualComponent.escrow,
            "Escrow do not match",
        )
    }

    @Test
    fun testGetComponentVersionDoc() {
        val actualComponent =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/api/2/components/TEST_COMPONENT_WITH_DOC/versions/1.0.0")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .toObject(ComponentV2::class.java)

        val expectedComponent = ComponentV2("TEST_COMPONENT_WITH_DOC", "Test Component with Doc", "user9")
        expectedComponent.doc =
            DocDTO(
                "TEST_COMPONENT_DOC",
                "1.2",
            )
        Assertions.assertEquals(
            expectedComponent.doc,
            actualComponent.doc,
            "Components do not match",
        )
    }
}
