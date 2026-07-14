package org.octopusden.octopus.components.registry.server.openapi

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
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
import java.nio.file.Files
import java.nio.file.Path

/**
 * TD-003: generates the v4 OpenAPI document and gates drift.
 *
 * Boots the SAME DB-backed H2 context as [BasicFunctionalitySmokeTest] (profiles
 * `common` + `smoke`, `auth-server.disabled=true`) so all four `@ConditionalOnDatabaseEnabled`
 * v4 controllers register and the Spring test-context cache is SHARED with that smoke test
 * (no extra context boot in the [1.0] gate). The boot signature must stay byte-for-byte
 * identical to [BasicFunctionalitySmokeTest] or the cache forks — treat that as a hard rule.
 *
 * Deliberately UNtagged (no `@Tag("integration")`) so it runs under `test` -> `check` ->
 * `build` and gates every build.
 *
 * Behaviour:
 *  - hits `GET /v3/api-docs/v4` (served by [OpenApiV4Config]'s GroupedOpenApi),
 *  - canonicalizes (recursive key sort + fixed 2-space `\n` pretty-print + trailing newline)
 *    so the bytes are stable regardless of host/JVM map-iteration order,
 *  - always writes `build/openapi/v4.json` (the AC-1 artifact),
 *  - in refresh mode (`-Popenapi.refresh=true`) stops there,
 *  - otherwise asserts byte-equality against the committed classpath resource
 *    `/openapi/v4.json`, with a message pointing at `generateOpenApiDocs`.
 */
@ExtendWith(SpringExtension::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = [ComponentRegistryServiceApplication::class],
    properties = ["auth-server.disabled=true"],
)
@AutoConfigureMockMvc
@ActiveProfiles("common", "smoke")
class OpenApiV4SpecTest {
    companion object {
        init {
            BaseComponentsRegistryServiceTest.configureSpringAppTestDataDir()
        }

        private const val COMMITTED_RESOURCE = "/openapi/v4.json"
        private val MAPPER = ObjectMapper()

        /** Recursively sort object member names; arrays keep element order. */
        private fun sort(node: JsonNode): JsonNode =
            when {
                node.isObject -> JsonNodeFactory.instance.objectNode().also { out ->
                    node.fieldNames().asSequence().sorted().forEach { name ->
                        out.set<JsonNode>(name, sort(node.get(name)))
                    }
                }
                node.isArray -> JsonNodeFactory.instance.arrayNode().also { out ->
                    node.forEach { out.add(sort(it)) }
                }
                else -> node
            }

        private fun canonicalize(rawJson: String): String {
            val sorted = sort(MAPPER.readTree(rawJson))
            val indenter = DefaultIndenter("  ", "\n")
            val printer = DefaultPrettyPrinter().apply {
                indentObjectsWith(indenter)
                indentArraysWith(indenter)
            }
            return MAPPER.writer(printer).writeValueAsString(sorted) + "\n"
        }
    }

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    @DisplayName("v4 OpenAPI spec is generated, covers the v4 surface, and matches the committed v4.json")
    fun `v4 openapi spec is generated and matches committed`() {
        // Decode the response bytes as UTF-8 explicitly rather than via contentAsString (which uses
        // the response's charset) so the gate is not host/charset-sensitive.
        val raw = mvc
            .perform(get("/v3/api-docs/v4"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsByteArray
            .toString(Charsets.UTF_8)
        val regenerated = canonicalize(raw)

        // AC-1: always emit build/openapi/v4.json.
        val outDir = System.getProperty("openapi.outputDir") ?: "build/openapi"
        val outFile = Path.of(outDir, "v4.json")
        Files.createDirectories(outFile.parent)
        Files.writeString(outFile, regenerated)

        // Coverage assertions (codified, not manual): the six v4 groups present, no legacy paths.
        val paths = MAPPER.readTree(regenerated).get("paths")
        Assertions.assertNotNull(paths, "generated spec has no `paths` object")
        val pathKeys = paths.fieldNames().asSequence().toList()
        listOf(
            "/rest/api/4/components",
            "/rest/api/4/audit",
            "/rest/api/4/admin",
            "/rest/api/4/config",
            "/rest/api/4/health",
            "/rest/api/4/info",
            "/auth/me",
        ).forEach { prefix ->
            Assertions.assertTrue(
                pathKeys.any { it.startsWith(prefix) },
                "v4 spec is missing any path under `$prefix` (got: $pathKeys)",
            )
        }
        listOf("/rest/api/1", "/rest/api/2", "/rest/api/3").forEach { legacy ->
            Assertions.assertTrue(
                pathKeys.none { it.startsWith(legacy) },
                "v4 spec must not include legacy `$legacy` paths (got: $pathKeys)",
            )
        }
        // OpenAPI path identity ignores the parameter NAME, so `/components/{id}` and
        // `/components/{idOrName}` are the SAME path — emitting both is invalid and breaks
        // generators (openapi-typescript). Assert no two templates collide after normalizing
        // every `{param}` to `{}`.
        val normalizedToOriginals = pathKeys.groupBy { it.replace(Regex("\\{[^}]+}"), "{}") }
        val collisions = normalizedToOriginals.filterValues { it.size > 1 }
        Assertions.assertTrue(
            collisions.isEmpty(),
            "v4 spec has path templates that collide after parameter-name normalization " +
                "(same path, different param name): ${collisions.values}",
        )

        if (System.getProperty("openapi.refresh") == "true") return

        // AC-3: drift gate.
        val committed = OpenApiV4SpecTest::class.java.getResource(COMMITTED_RESOURCE)?.readText()
            ?: Assertions.fail(
                "committed spec resource $COMMITTED_RESOURCE is missing — run " +
                    "`./gradlew :components-registry-service-server:generateOpenApiDocs` and commit it",
            )
        Assertions.assertEquals(
            committed,
            regenerated,
            "OpenAPI v4 spec drift: the committed src/main/resources/openapi/v4.json is stale. " +
                "Run `./gradlew :components-registry-service-server:generateOpenApiDocs` and commit the updated v4.json.",
        )
    }
}
