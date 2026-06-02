package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import java.nio.file.Paths
import java.util.UUID

/**
 * Closes the open #192 review findings about v4 write-side validation: enum-typed
 * fields (`productType`, `buildSystem`, `repositoryType`, `packageType`,
 * `escrow.generation`) and `versionRange` syntax on PATCH used to be accepted
 * verbatim, with the resolver silently dropping invalid values or, in the case
 * of malformed ranges, breaking the enumeration on the read path.
 *
 * Each test sends one specific bad value and asserts 400. Pre-fix, all of these
 * either persisted the bad value (and would later surface as a 500 on read) or
 * silently succeeded.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class V4WriteValidationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(V4WriteValidationTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun postCreate(body: String) =
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )

    /**
     * Minimal valid create body. The only hard create requirement is
     * `baseConfiguration.build.buildSystem`. A `group` is included for
     * backward-compat coverage but is accepted-and-ignored by the API (R1:
     * group is migration-owned aggregator membership, never assigned via the
     * API). Tests that previously seeded with `{"name": "..."}` or
     * `{"name": "...", "baseConfiguration": {}}` route through this helper so
     * the field-override / PATCH cases that follow run against a real saved
     * component.
     */
    private fun validSeedBody(name: String): String =
        """{"name": "$name",""" +
            """"group":{"groupKey":"org.example.test","isFake":false},""" +
            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""

    @Test
    @DisplayName("CREATE rejects unknown productType")
    fun create_rejects_unknownProductType() {
        val body = """{"name": "validation-test-comp-pt", "productType": "NOT_A_REAL_PRODUCT_TYPE"}"""
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects unknown baseConfiguration.build.buildSystem")
    fun create_rejects_unknownBuildSystem() {
        // Sends a complete, strict-contract-valid envelope EXCEPT for the
        // unknown enum token — so the 400 here is specifically due to
        // `validateBuildSystem("NOT_A_BUILD_SYSTEM")`, not due to a missing
        // top-level required field that would otherwise short-circuit the
        // assertion.
        val body =
            """{"name": "validation-test-comp-bs",""" +
                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                """"baseConfiguration": {"build": {"buildSystem": "NOT_A_BUILD_SYSTEM"}}}"""
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects unknown vcsEntry.repositoryType")
    fun create_rejects_unknownRepositoryType() {
        val body =
            """
            {
              "name": "validation-test-comp-rt",
              "baseConfiguration": {
                "vcsEntries": [
                  {"vcsPath": "ssh://git@example/x.git", "repositoryType": "NOT_A_REPO_TYPE"}
                ]
              }
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects unknown packages[].packageType")
    fun create_rejects_unknownPackageType() {
        val body =
            """
            {
              "name": "validation-test-comp-pkg",
              "baseConfiguration": {
                "packages": [
                  {"packageType": "TARBALL", "packageName": "some-name"}
                ]
              }
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("POST /field-overrides rejects scalar build.buildSystem with unknown enum value")
    fun fieldOverride_rejects_unknownBuildSystemScalar() {
        // Seed a minimal valid component first.
        val createBody = validSeedBody("validation-test-comp-bs-fo")
        val seedResponse =
            postCreate(createBody)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val id = objectMapper.readTree(seedResponse)["id"].asText()

        val payload =
            """
            {
              "overriddenAttribute": "build.buildSystem",
              "versionRange": "[99.0.0,)",
              "value": "NOT_A_BUILD_SYSTEM"
            }
            """.trimIndent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName(
        "field-override marker `vcs.settings` rejects unknown vcsEntries[].repositoryType",
    )
    fun fieldOverride_rejects_unknownRepositoryTypeInMarker() {
        val createBody = validSeedBody("validation-test-comp-rt-fo")
        val seedResponse =
            postCreate(createBody).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        val id = objectMapper.readTree(seedResponse)["id"].asText()

        val payload =
            """
            {
              "overriddenAttribute": "vcs.settings",
              "versionRange": "[99.0.0,)",
              "markerChildren": {
                "vcsEntries": [
                  {"vcsPath": "ssh://git@example/x.git", "repositoryType": "NOT_A_REPO_TYPE"}
                ]
              }
            }
            """.trimIndent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName(
        "field-override marker `distribution.packages` rejects unknown packages[].packageType",
    )
    fun fieldOverride_rejects_unknownPackageTypeInMarker() {
        val createBody = validSeedBody("validation-test-comp-pkg-fo")
        val seedResponse =
            postCreate(createBody).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        val id = objectMapper.readTree(seedResponse)["id"].asText()

        val payload =
            """
            {
              "overriddenAttribute": "distribution.packages",
              "versionRange": "[99.0.0,)",
              "markerChildren": {
                "packages": [
                  {"packageType": "TARBALL", "packageName": "some-name"}
                ]
              }
            }
            """.trimIndent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects unknown baseConfiguration.escrow.generation")
    fun create_rejects_unknownEscrowGeneration() {
        val body =
            """{"name": "validation-test-comp-gen",""" +
                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                """"baseConfiguration": {"build": {"buildSystem": "MAVEN"}, """ +
                """"escrow": {"generation": "NOT_A_GENERATION_MODE"}}}"""
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("POST /field-overrides rejects scalar escrow.generation with unknown enum value")
    fun fieldOverride_rejects_unknownEscrowGenerationScalar() {
        val createBody = validSeedBody("validation-test-comp-gen-fo")
        val seedResponse =
            postCreate(createBody)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val id = objectMapper.readTree(seedResponse)["id"].asText()

        val payload =
            """
            {
              "overriddenAttribute": "escrow.generation",
              "versionRange": "[99.0.0,)",
              "value": "NOT_A_GENERATION_MODE"
            }
            """.trimIndent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("PATCH rejects unknown baseConfiguration.escrow.generation")
    fun patch_rejects_unknownEscrowGeneration() {
        val createBody = validSeedBody("validation-test-comp-gen-patch")
        val seedResponse =
            postCreate(createBody)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()
        val patchBody =
            """{"version": $versionLock, "baseConfiguration": {"escrow": {"generation": "NOT_A_GENERATION_MODE"}}}"""
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("PATCH baseConfiguration.versionRange with bad syntax returns 400 (pre-existing)")
    fun patch_rejects_invalidVersionRangeSyntax() {
        // Seed a minimal valid component first.
        val createBody = validSeedBody("validation-test-comp-vr")
        val seedResponse =
            postCreate(createBody)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()
        val patchBody =
            """{"version": $versionLock, "baseConfiguration": {"versionRange": "this-is-not-a-version-range"}}"""
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------
    // SYS-040 write-side labels canonicalisation
    //
    // The read path trims and dedupes `?labels=…` (see SYS-040 controller
    // normalisation). Without symmetric write-side canonicalisation, a
    // component created with labels=["A "] would persist "A " in
    // component_labels and become unreachable from `?labels=A` filtering
    // (the JPA cb.equal compares raw column values). Canonicalising at
    // write time (trim + drop blank + dedupe) keeps the contract
    // consistent across both paths.
    //
    // The 400 case is for "non-empty input that canonicalises to nothing"
    // — an empty `labels: []` is still a legitimate "clear labels"
    // operation and stays a 2xx.
    // -------------------------------------------------------------------

    private fun uniqueSuffix() = UUID.randomUUID().toString().take(6)

    @Test
    @DisplayName("SYS-040: CREATE with labels=[\"A \"] persists trimmed \"A\" (write-side trim)")
    fun `SYS-040 create with trailing-space label persists trimmed`() {
        val suffix = uniqueSuffix()
        val name = "labels_trim_create_$suffix"
        val labelRaw = "lblcreate_$suffix" // canonical
        val labelWithSpace = "$labelRaw " // sent by caller
        val body =
            """{"name":"$name","labels":["$labelWithSpace"],""" +
                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""
        postCreate(body).andExpect(status().isCreated)

        // After write, GET /meta/labels must include the canonical form and NOT the raw one.
        val metaBody =
            mvc
                .perform(get("/rest/api/4/components/meta/labels").with(viewerJwt()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val codes = objectMapper.readTree(metaBody).map { it.asText() }
        assert(codes.contains(labelRaw)) {
            "expected canonical '$labelRaw' in $codes (trim should have applied)"
        }
        assert(!codes.contains(labelWithSpace)) {
            "expected raw '$labelWithSpace' NOT in $codes (trim should have stripped trailing space)"
        }
    }

    @Test
    @DisplayName("SYS-040: CREATE with labels=[\" \"] (single blank) returns 400")
    fun `SYS-040 create with single blank label returns 400`() {
        val name = "labels_blankonly_create_${uniqueSuffix()}"
        val body = """{"name":"$name","labels":[" "]}"""
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("SYS-040: CREATE with labels=[\"A\",\"A\",\" A \"] persists single \"A\" (dedupe after trim)")
    fun `SYS-040 create dedupes labels after trim`() {
        val suffix = uniqueSuffix()
        val name = "labels_dedupe_create_$suffix"
        val label = "lbldedupe_$suffix"
        val body =
            """{"name":"$name","labels":["$label","$label"," $label "],""" +
                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""
        val created =
            postCreate(body)
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString
        // ComponentDetailResponse.labels is a Set<String>; the response
        // should carry exactly one entry for this component after dedupe.
        val labels = objectMapper.readTree(created)["labels"].map { it.asText() }
        assert(labels == listOf(label)) {
            "expected exactly [$label] after dedupe; got $labels"
        }
    }

    @Test
    @DisplayName("SYS-040: PATCH with labels=[\"A \"] persists trimmed \"A\" (write-side trim on update)")
    fun `SYS-040 patch with trailing-space label persists trimmed`() {
        val suffix = uniqueSuffix()
        val name = "labels_trim_patch_$suffix"
        val labelRaw = "lblpatch_$suffix"
        val labelWithSpace = "$labelRaw "

        // Seed a minimal component, then PATCH labels.
        val seedBody = validSeedBody(name)
        val seedResponse =
            postCreate(seedBody)
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val patchBody = """{"version":$versionLock,"labels":["$labelWithSpace"]}"""
        val patched =
            mvc
                .perform(
                    patch("/rest/api/4/components/$id")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody),
                ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        val labels = objectMapper.readTree(patched)["labels"].map { it.asText() }
        assert(labels == listOf(labelRaw)) {
            "expected canonical [$labelRaw] after patch; got $labels"
        }
    }

    @Test
    @DisplayName("SYS-040: PATCH with labels=[\" \"] (single blank) returns 400")
    fun `SYS-040 patch with single blank label returns 400`() {
        val suffix = uniqueSuffix()
        val seedBody = validSeedBody("labels_blankonly_patch_$suffix")
        val seedResponse =
            postCreate(seedBody)
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()
        val patchBody = """{"version":$versionLock,"labels":[" "]}"""
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------
    // Partial-overlap rejection (R3 / schema-spec §3.5).
    //
    // Mirrors the Portal-side preview from PR #65: a new field-override
    // range that partially overlaps a sibling on the same attribute is
    // rejected with 400. Strict containment and disjoint ranges remain
    // allowed; equal ranges are still caught by the DB UNIQUE constraint.
    // -------------------------------------------------------------------

    private fun seedComponentForOverlap(suffix: String): String {
        val createBody = validSeedBody("validation-test-comp-overlap-$suffix")
        val seedResponse =
            postCreate(createBody)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(seedResponse)["id"].asText()
    }

    private fun postFieldOverride(componentId: String, body: String) =
        mvc.perform(
            post("/rest/api/4/components/$componentId/field-overrides")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    @Test
    @DisplayName("R3: POST /field-overrides rejects a range partially overlapping a sibling on the same attribute")
    fun fieldOverride_rejects_partialOverlapWithSibling() {
        val id = seedComponentForOverlap("partial-${uniqueSuffix()}")
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0,3.0)","value":"11"}""",
        ).andExpect(status().isCreated)
        // [2.0,4.0) overlaps [1.0,3.0) on [2.0,3.0); neither contains the other → reject.
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[2.0,4.0)","value":"17"}""",
        ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("R3: POST /field-overrides accepts a range strictly contained inside a sibling (schema-spec §3.5)")
    fun fieldOverride_allows_strictContainment() {
        val id = seedComponentForOverlap("contained-${uniqueSuffix()}")
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0,5.0)","value":"11"}""",
        ).andExpect(status().isCreated)
        // [2.0,3.0) is fully inside [1.0,5.0) → strict containment, accepted.
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[2.0,3.0)","value":"17"}""",
        ).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("R3: POST /field-overrides accepts a disjoint range on the same attribute")
    fun fieldOverride_allows_disjoint() {
        val id = seedComponentForOverlap("disjoint-${uniqueSuffix()}")
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0,2.0)","value":"11"}""",
        ).andExpect(status().isCreated)
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[5.0,6.0)","value":"17"}""",
        ).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("R3: POST /field-overrides rejects a semantically-equal range differing only by whitespace")
    fun fieldOverride_rejects_semanticEqualWhitespace() {
        val id = seedComponentForOverlap("ws-${uniqueSuffix()}")
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0,2.0)","value":"11"}""",
        ).andExpect(status().isCreated)
        // Same range with a stray space — exact-string UNIQUE would miss it
        // without input normalisation; semantic-equal check rejects it.
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0, 2.0)","value":"17"}""",
        ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("R3: POST /field-overrides rejects a semantically-equal range differing only by trailing zero")
    fun fieldOverride_rejects_semanticEqualTrailingZero() {
        val id = seedComponentForOverlap("tz-${uniqueSuffix()}")
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0,2.0)","value":"11"}""",
        ).andExpect(status().isCreated)
        // [1,2) describes the same versions as [1.0,2.0) per Maven semantics
        // — DefaultArtifactVersion sees `1` and `1.0` as equal.
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1,2)","value":"17"}""",
        ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("R3: POST /field-overrides ignores overlap against a sibling on a DIFFERENT attribute")
    fun fieldOverride_allows_overlapAcrossAttributes() {
        val id = seedComponentForOverlap("diff-attr-${uniqueSuffix()}")
        postFieldOverride(
            id,
            """{"overriddenAttribute":"build.javaVersion","versionRange":"[1.0,3.0)","value":"11"}""",
        ).andExpect(status().isCreated)
        // Same range on a different attribute — not a conflict by the
        // partial-overlap rule (rule is per-attribute).
        postFieldOverride(
            id,
            """{"overriddenAttribute":"jira.releaseVersionFormat","versionRange":"[2.0,4.0)","value":"${'$'}major"}""",
        ).andExpect(status().isCreated)
    }
}
