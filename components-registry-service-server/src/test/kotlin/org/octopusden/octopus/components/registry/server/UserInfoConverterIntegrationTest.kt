package org.octopusden.octopus.components.registry.server

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.time.Instant
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get as mvcGet

/**
 * Integration test for the REAL JWT → `UserInfoGrantedAuthoritiesConverter` → authorities
 * path. The other 15 `@SpringBootTest` classes inject `SecurityMockMvcRequestPostProcessors.jwt()`
 * which builds a `JwtAuthenticationToken` directly and bypasses the converter; this test
 * fills that gap so a cloud-commons upgrade or a wiring regression here fails CI.
 *
 * Strategy:
 *  - `@MockBean JwtDecoder` — accepts any `Bearer <token>` and returns a `Jwt` whose
 *    `tokenValue` equals the incoming token, so the converter sees a real token value.
 *  - Real `AuthServerClient` (NOT mocked) — lets it actually hit WireMock's `/userinfo`
 *    stub, exercising the full library path.
 *  - WireMock stubs: `/.well-known/openid-configuration` for `AuthServerClient.init{}`,
 *    and two `/userinfo` responses keyed on the bearer token value.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class UserInfoConverterIntegrationTest {
    init {
        val testResourcesPath =
            Paths.get(UserInfoConverterIntegrationTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @MockBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var mvc: MockMvc

    @org.junit.jupiter.api.BeforeEach
    fun stubDecoder() {
        // Accept any bearer and echo it back as the Jwt.tokenValue so the authorities
        // converter calls AuthServerClient.getUserInfo(<that token>) → WireMock.
        org.mockito.Mockito
            .`when`(jwtDecoder.decode(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer { inv ->
                val token = inv.getArgument<String>(0)
                val username = if (token == ADMIN_TOKEN) "alice" else "carol"
                Jwt
                    .withTokenValue(token)
                    .header("alg", "none")
                    .claim("preferred_username", username)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build()
            }
    }

    @Test
    @DisplayName("admin token → /userinfo returns ADMIN → /auth/me exposes ROLE_ADMIN")
    fun adminRolesFlowThroughConverter() {
        mvc
            .perform(mvcGet("/auth/me").header("Authorization", "Bearer $ADMIN_TOKEN"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.roles[0].name").value("ROLE_ADMIN"))
            .andExpect(jsonPath("$.roles[0].permissions", org.hamcrest.Matchers.hasItem("ACCESS_COMPONENTS")))
    }

    @Test
    @DisplayName("viewer token → /userinfo returns REGISTRY_VIEWER → cannot POST v4 create (403)")
    fun viewerCannotCreateV4() {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .header("Authorization", "Bearer $VIEWER_TOKEN")
                    .contentType("application/json")
                    .content("""{"name":"x","displayName":"x"}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("viewer token → GET /rest/api/4/components allowed (ACCESS_COMPONENTS only)")
    fun viewerReadsV4List() {
        mvc
            .perform(mvcGet("/rest/api/4/components").header("Authorization", "Bearer $VIEWER_TOKEN"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("no token → v4 GET /components is public (200)")
    fun anonymousCanReadV4List() {
        mvc
            .perform(mvcGet("/rest/api/4/components"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("no token → v4 write is still 401")
    fun missingTokenOnWriteIs401() {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .contentType("application/json")
                    .content("""{"name":"x","displayName":"x"}"""),
            ).andExpect(status().isUnauthorized)
    }

    companion object {
        private const val ADMIN_TOKEN = "test-admin-token"
        private const val VIEWER_TOKEN = "test-viewer-token"

        private lateinit var wireMock: WireMockServer

        @JvmStatic
        @BeforeAll
        fun startAuthStub() {
            wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())
            wireMock.start()

            val base = "http://localhost:${wireMock.port()}/realms/test"
            wireMock.stubFor(
                get(urlEqualTo("/realms/test/.well-known/openid-configuration"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                """
                                {
                                  "issuer": "$base",
                                  "authorization_endpoint": "$base/protocol/openid-connect/auth",
                                  "token_endpoint": "$base/protocol/openid-connect/token",
                                  "userinfo_endpoint": "$base/protocol/openid-connect/userinfo",
                                  "jwks_uri": "$base/protocol/openid-connect/certs",
                                  "end_session_endpoint": "$base/protocol/openid-connect/logout"
                                }
                                """.trimIndent(),
                            ),
                    ),
            )

            wireMock.stubFor(
                get(urlEqualTo("/realms/test/protocol/openid-connect/userinfo"))
                    .withHeader("Authorization", equalTo("Bearer $ADMIN_TOKEN"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"roles":["ADMIN"],"groups":[]}"""),
                    ),
            )
            wireMock.stubFor(
                get(urlEqualTo("/realms/test/protocol/openid-connect/userinfo"))
                    .withHeader("Authorization", equalTo("Bearer $VIEWER_TOKEN"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"roles":["REGISTRY_VIEWER"],"groups":[]}"""),
                    ),
            )
        }

        @JvmStatic
        @AfterAll
        fun stopAuthStub() {
            if (::wireMock.isInitialized) wireMock.stop()
        }

        @DynamicPropertySource
        @JvmStatic
        fun authServerProperties(registry: DynamicPropertyRegistry) {
            registry.add("auth-server.url") { "http://localhost:${wireMock.port()}" }
            registry.add("auth-server.realm") { "test" }
        }
    }
}
