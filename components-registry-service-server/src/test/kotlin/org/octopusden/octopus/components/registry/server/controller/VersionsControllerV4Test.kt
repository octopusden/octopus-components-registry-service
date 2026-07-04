package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.dto.v4.VersionPreviewFormats
import org.octopusden.octopus.components.registry.server.dto.v4.VersionPreviewOverride
import org.octopusden.octopus.components.registry.server.dto.v4.VersionPreviewRequest
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths

/**
 * SYS-059: `POST /rest/api/4/versions/preview` renders a DetailedComponentVersion
 * from ad-hoc formats. Exercised through the full SpringBootTest context so the
 * real WebSecurityConfig gates the endpoint (authenticated-only under the
 * `rest/api/4` catch-all).
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class VersionsControllerV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("SYS-059: authenticated preview returns 200 with rendered coordinates")
    fun `SYS-059 authenticated preview returns rendered coordinates`() {
        val request =
            VersionPreviewRequest(
                version = "1.2.3",
                base =
                    VersionPreviewFormats(
                        minorVersionFormat = "\$major.\$minor",
                        releaseVersionFormat = "\$major.\$minor.\$service",
                        lineVersionFormat = "\$major.\$minor",
                    ),
                overrides =
                    listOf(
                        VersionPreviewOverride(
                            versionRange = "(,1.0.107)",
                            releaseVersionFormat = "\$major.\$minor.\$service-\$fix",
                        ),
                    ),
            )

        mvc
            .perform(
                post("/rest/api/4/versions/preview")
                    .with(viewerJwt())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)),
            ).andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            // 1.2.3 is outside (,1.0.107) → base release format applies.
            .andExpect(jsonPath("$.releaseVersion.version").value("1.2.3"))
            .andExpect(jsonPath("$.minorVersion.version").value("1.2"))
            .andExpect(jsonPath("$.rcVersion.version").value("1.2.3_RC"))
    }

    @Test
    @DisplayName("SYS-059: an unauthenticated preview is rejected with 401")
    fun `SYS-059 unauthenticated preview is 401`() {
        val request =
            VersionPreviewRequest(
                version = "1.2.3",
                base = VersionPreviewFormats(minorVersionFormat = "\$major.\$minor", releaseVersionFormat = "\$major.\$minor.\$service"),
            )

        mvc
            .perform(
                post("/rest/api/4/versions/preview")
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("SYS-059: a non-numeric version is rejected with 400")
    fun `SYS-059 invalid version is 400`() {
        val request =
            VersionPreviewRequest(
                version = "not-a-version",
                base = VersionPreviewFormats(minorVersionFormat = "\$major.\$minor", releaseVersionFormat = "\$major.\$minor.\$service"),
            )

        mvc
            .perform(
                post("/rest/api/4/versions/preview")
                    .with(viewerJwt())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("SYS-059: preview output equals detailed-version for the same effective config (SUB@3.0.0)")
    fun `SYS-059 preview matches detailed-version for a real component`() {
        // Parity guard: read the resolved Jira formats of a real seeded component
        // (SUB @ 3.0.0 — exercises a custom version prefix AND hotfix) from the v2
        // jira-component endpoint, feed them verbatim into the preview, and assert
        // the rendered coordinates match GET .../detailed-version for that version.
        val component = "SUB"
        val version = "3.0.0"

        val reference = getJson("/rest/api/2/components/$component/versions/$version/detailed-version", DetailedComponentVersion::class.java)
        val jira = getJson("/rest/api/2/components/$component/versions/$version/jira-component", JiraComponentVersionDTO::class.java).component
        val cvf = jira.componentVersionFormat

        val request =
            VersionPreviewRequest(
                version = version,
                technical = jira.technical,
                // Eligibility is VCS-derived in the persisted path; the seeded SUB
                // has a hotfix branch, so detailed-version renders a hotfix coordinate.
                hotfixEnabled = reference.hotfixVersion != null,
                base =
                    VersionPreviewFormats(
                        minorVersionFormat = cvf.majorVersionFormat,
                        releaseVersionFormat = cvf.releaseVersionFormat,
                        buildVersionFormat = cvf.buildVersionFormat,
                        lineVersionFormat = cvf.lineVersionFormat,
                        hotfixVersionFormat = cvf.hotfixVersionFormat,
                        versionPrefix = jira.componentInfo.versionPrefix,
                        versionFormat = jira.componentInfo.versionFormat,
                    ),
            )

        val preview =
            objectMapper.readValue(
                mvc
                    .perform(
                        post("/rest/api/4/versions/preview")
                            .with(viewerJwt())
                            .accept(MediaType.APPLICATION_JSON)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString,
                DetailedComponentVersion::class.java,
            )

        // The `component` field is a display name preview cannot know (it takes no
        // component), so normalise it; every version coordinate must match.
        assertEquals(reference.copy(component = preview.component), preview)
    }

    private fun <T> getJson(
        url: String,
        type: Class<T>,
    ): T =
        objectMapper.readValue(
            mvc.perform(get(url).accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk).andReturn().response.contentAsString,
            type,
        )

    companion object {
        // application-common.yml resolves work-dir from this property; without it
        // the @SpringBootTest context fails to start. Pattern per InfoControllerV4Test.
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(VersionsControllerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
