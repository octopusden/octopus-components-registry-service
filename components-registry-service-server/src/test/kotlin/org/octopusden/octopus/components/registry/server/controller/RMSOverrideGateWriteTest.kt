package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuild
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildsResult
import org.octopusden.octopus.components.registry.server.service.rms.RMSClient
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * End-to-end proof that `RMSOverrideGate` is actually wired into the real write endpoints — the
 * gate's own decision logic is already covered in isolation (`RMSOverrideGateTest`); this only
 * proves `ComponentManagementServiceImpl` really calls it and the HTTP layer maps its exceptions
 * correctly. Integration test (ft-db = H2 + auto-migrate, no Testcontainers), mirroring
 * `ComponentFieldOverridesPatchTest`'s harness, with `RMSClient` mocked and the feature force-enabled.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@TestPropertySource(properties = ["release-management-service.enabled=true", "release-management-service.url=http://mock-rms.example.com"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class RMSOverrideGateWriteTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var rmsClient: RMSClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(RMSOverrideGateWriteTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("creating a field override that disagrees with RMS's ACTUAL value is rejected 409")
    fun `disagreeing field override create is rejected`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("2", "17", null))))
        val id = newComponent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"build.javaVersion","versionRange":"[1,5)","value":"11"}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("creating a field override that matches RMS's ACTUAL value is permitted")
    fun `matching field override create is permitted`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("2", "17", null))))
        val id = newComponent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"build.javaVersion","versionRange":"[1,5)","value":"17"}"""),
            ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("creating a Maven field override that disagrees with RMS's ACTUAL value is rejected 409 — the gate isn't Java-only")
    fun `disagreeing Maven field override create is rejected`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("2", null, "3.3.9"))))
        val id = newComponent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"build.mavenVersion","versionRange":"[1,5)","value":"3.3.6"}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("creating a Maven field override that matches RMS's ACTUAL value is permitted — the gate isn't Java-only")
    fun `matching Maven field override create is permitted`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("2", null, "3.3.9"))))
        val id = newComponent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"build.mavenVersion","versionRange":"[1,5)","value":"3.3.9"}"""),
            ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("changing both javaVersion and mavenVersion in one PATCH fetches RMS only once for the component")
    fun `changing both java and maven in one PATCH calls RMS only once`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("1", "11", "3.3.9"))))
        val id = newComponent()

        patchComponent(id, """"baseConfiguration":{"build":{"javaVersion":"11","mavenVersion":"3.3.9"}}""")

        verify(rmsClient, times(1)).getBuilds(anyString())
    }

    @Test
    @DisplayName("an unchanged resend of the base javaVersion is never blocked, even though it disagrees with ACTUAL")
    fun `unchanged resend is never blocked`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("2", "21", null))))
        val id = newComponent(javaVersion = "11")
        // Re-send the same javaVersion the component already has — must succeed despite disagreeing with ACTUAL.
        patchComponent(id, """"baseConfiguration":{"build":{"javaVersion":"11"}}""")
    }

    @Test
    @DisplayName("changing the base javaVersion to a disagreeing value via PATCH is rejected 409")
    fun `base config patch to a disagreeing value is rejected`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("2", "21", null))))
        val id = newComponent(javaVersion = "11")
        val version = currentVersion(id)
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":$version,"baseConfiguration":{"build":{"javaVersion":"8"}}}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("RMS being unreachable at write time blocks a real javaVersion change with 503")
    fun `RMS unreachable blocks a real change`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Unavailable)
        val id = newComponent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"build.javaVersion","versionRange":"[1,5)","value":"11"}"""),
            ).andExpect(status().isServiceUnavailable)
    }

    @Test
    @DisplayName("deleting a field override succeeds regardless of what ACTUAL reports — no gate call at all")
    fun `delete is never gated`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Unavailable)
        val id = newComponent()
        val overrideId = seedOverride(id, "build.javaVersion", "[1,5)")
        mvc
            .perform(delete("/rest/api/4/components/$id/field-overrides/$overrideId").with(adminJwt()))
            .andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName(
        "moving a field override's range onto ACTUAL-covered disagreeing territory is rejected, even though its value didn't change",
    )
    fun `moving an override range onto disagreeing territory is rejected`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("5", "17", null))))
        val id = newComponent()
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components/$id/field-overrides")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        // [1,3) is outside ACTUAL's [5,10) at creation time — no disagreement yet.
                        .content("""{"overriddenAttribute":"build.javaVersion","versionRange":"[1,3)","value":"11"}"""),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val overrideId = objectMapper.readTree(body)["id"].asText()

        // Same value (11), moved into ACTUAL-covered territory where it now disagrees.
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("5", "17", null))))
        mvc
            .perform(
                patch("/rest/api/4/components/$id/field-overrides/$overrideId")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"versionRange":"[5,10)"}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("creating a field override via the bulk desired-set PATCH is gated the same as the single-create endpoint")
    fun `bulk desired-set create of a disagreeing override is rejected`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("2", "17", null))))
        val id = newComponent()
        val version = currentVersion(id)
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":$version,"fieldOverrides":[""" +
                            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1,5)","value":"11"}]}""",
                    ),
            ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("updating a field override's value via the bulk desired-set PATCH is gated the same as the single-update endpoint")
    fun `bulk desired-set update to a disagreeing value is rejected`() {
        val id = newComponent()
        val overrideId = seedOverride(id, "build.javaVersion", "[1,5)")

        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("2", "21", null))))
        val version = currentVersion(id)
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":$version,"fieldOverrides":[""" +
                            """{"id":"$overrideId","overriddenAttribute":"build.javaVersion","versionRange":"[1,5)","value":"11"}]}""",
                    ),
            ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName(
        "recreating a field override with the same, still-disagreeing value after deleting it is rejected, just like any other write",
    )
    fun `recreating the same disagreeing override after delete is rejected`() {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Unavailable)
        val id = newComponent()
        val overrideId = seedOverride(id, "build.javaVersion", "[1,5)")
        mvc
            .perform(delete("/rest/api/4/components/$id/field-overrides/$overrideId").with(adminJwt()))
            .andExpect(status().is2xxSuccessful)

        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(listOf(RMSBuild("2", "17", null))))
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"build.javaVersion","versionRange":"[1,5)","value":"11"}"""),
            ).andExpect(status().isConflict)
    }

    private fun newComponent(javaVersion: String? = null): String {
        val name = "rms-gate-${UUID.randomUUID().toString().take(8)}"
        val build = if (javaVersion !=
            null
        ) {
            """"build":{"buildSystem":"MAVEN","javaVersion":"$javaVersion"}"""
        } else {
            """"build":{"buildSystem":"MAVEN"}"""
        }
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","componentOwner":"owner1",""" +
                                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                                """"baseConfiguration":{$build}}""",
                        ),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    // Bypasses the gate deliberately: this seeds fixture state, not exercising the write path under test.
    private fun seedOverride(
        componentId: String,
        attribute: String,
        range: String,
    ): String {
        `when`(rmsClient.getBuilds(anyString())).thenReturn(RMSBuildsResult.Available(emptyList()))
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components/$componentId/field-overrides")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"overriddenAttribute":"$attribute","versionRange":"$range","value":"17"}"""),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun currentVersion(componentId: String): Long = getComponent(componentId)["version"].asLong()

    private fun getComponent(componentId: String): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$componentId").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun patchComponent(
        componentId: String,
        fieldsWithoutVersion: String,
    ) {
        val payload = """{"version":${currentVersion(componentId)},$fieldsWithoutVersion}"""
        mvc
            .perform(
                patch("/rest/api/4/components/$componentId")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isOk)
    }
}
