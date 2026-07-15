package org.octopusden.octopus.components.registry.server.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The v1/v2/v3 read contracts (Feign clients) have live controllers on this
 * server but were absent from the swagger-ui group dropdown because the only
 * [org.springdoc.core.models.GroupedOpenApi] defined was `v4`. This gates the
 * legacy groups: each `GET /v3/api-docs/{v1,v2,v3}` must exist and expose its
 * own `/rest/api/{n}/…` surface.
 *
 * Boot signature is kept byte-identical to [OpenApiV4SpecTest] so the Spring
 * test-context cache is SHARED (no extra context boot in the [1.0] gate).
 */
@ExtendWith(SpringExtension::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = [ComponentRegistryServiceApplication::class],
    properties = ["auth-server.disabled=true"],
)
@AutoConfigureMockMvc
@ActiveProfiles("common", "smoke")
class OpenApiLegacyGroupsTest {
    companion object {
        init {
            BaseComponentsRegistryServiceTest.configureSpringAppTestDataDir()
        }

        private val MAPPER = ObjectMapper()
    }

    @Autowired
    private lateinit var mvc: MockMvc

    private fun pathKeysOf(group: String): List<String> {
        val raw = mvc
            .perform(get("/v3/api-docs/$group"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsByteArray
            .toString(Charsets.UTF_8)
        val paths = MAPPER.readTree(raw).get("paths")
        Assertions.assertNotNull(paths, "group `$group` spec has no `paths` object")
        return paths.fieldNames().asSequence().toList()
    }

    @Test
    @DisplayName("v1/v2/v3 OpenAPI groups are served and each exposes only its own /rest/api/{n} surface")
    fun `legacy groups are exposed`() {
        mapOf(
            "v1" to Pair("/rest/api/1/", listOf("/rest/api/2/", "/rest/api/3/", "/rest/api/4/")),
            "v2" to Pair("/rest/api/2/", listOf("/rest/api/1/", "/rest/api/3/", "/rest/api/4/")),
            "v3" to Pair("/rest/api/3/", listOf("/rest/api/1/", "/rest/api/2/", "/rest/api/4/")),
        ).forEach { (group, expectation) ->
            val (ownPrefix, foreignPrefixes) = expectation
            val keys = pathKeysOf(group)
            Assertions.assertTrue(
                keys.any { it.startsWith(ownPrefix) },
                "group `$group` is missing any path under `$ownPrefix` (got: $keys)",
            )
            foreignPrefixes.forEach { foreign ->
                Assertions.assertTrue(
                    keys.none { it.startsWith(foreign) },
                    "group `$group` must not include `$foreign` paths (got: $keys)",
                )
            }
        }
    }
}
