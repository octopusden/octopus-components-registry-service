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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * Strict-contract validation introduced by the UI-swift-sloth iteration.
 *
 * The Portal's Create Component flow now requires `group` and
 * `baseConfiguration.build.buildSystem` (frontend UX). The server is the
 * source of truth: it MUST reject create payloads missing either field with
 * 400 so a misbehaving client cannot land an under-specified component.
 *
 * On PATCH, semantics differ:
 *  - `group: null` continues to mean "don't touch" (Jackson cannot
 *    distinguish field-absent from explicit-null without a presence-preserving
 *    DTO, and every untouched-group PATCH today carries `group == null`).
 *  - `clearGroup: true` becomes invalid because there is no path for a
 *    component to legitimately exist without a group. Service rejects with 400.
 *  - `clearGroup: false` (explicit) with no `group` key is the canonical
 *    no-op shape the Portal sends when the group field is untouched —
 *    must remain 2xx and not modify the existing group.
 *
 * `baseConfiguration.build.buildSystem` may be omitted from PATCH (PATCH
 * cannot blank an already-set scalar via "field absent"; the BASE row is
 * already valid). Only CREATE is gated.
 *
 * All test fixtures use RFC 2606 reserved-for-examples placeholders
 * (`com.example.*`) — no real customer / product domain in source.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class StrictContractTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(StrictContractTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun unique(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun postCreate(body: String) =
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun validCreateBody(name: String, groupKey: String = "org.example.test") =
        """
        {
          "name": "$name",
          "group": {"groupKey": "$groupKey", "isFake": false},
          "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
        }
        """.trimIndent()

    @Test
    @DisplayName("CREATE returns 400 when 'group' is missing")
    fun create_rejects_missingGroup() {
        // The @DisplayName lists 400 status; the error message naming `group`
        // is also part of the contract (the frontend / non-UI clients rely
        // on the field name being mentioned). Asserting both pins the
        // contract surface.
        val body =
            """
            {
              "name": "${unique("strict-no-group")}",
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", org.hamcrest.Matchers.containsString("group")))
    }

    @Test
    @DisplayName("CREATE returns 400 when 'baseConfiguration' is missing")
    fun create_rejects_missingBaseConfiguration() {
        val body =
            """
            {
              "name": "${unique("strict-no-bc")}",
              "group": {"groupKey": "org.example.test", "isFake": false}
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE returns 400 when 'baseConfiguration.build.buildSystem' is missing")
    fun create_rejects_missingBuildSystem() {
        val body =
            """
            {
              "name": "${unique("strict-no-bs")}",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {}}
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE returns 400 when 'baseConfiguration.build' is missing")
    fun create_rejects_missingBuildBlock() {
        val body =
            """
            {
              "name": "${unique("strict-no-build")}",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {}
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE returns 400 when group.groupKey is blank (whitespace-only)")
    fun create_rejects_blankGroupKey() {
        val body =
            """
            {
              "name": "${unique("strict-blank-grp")}",
              "group": {"groupKey": "  ", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", org.hamcrest.Matchers.containsString("groupKey")))
    }

    @Test
    @DisplayName("CREATE accepts payload with required fields present (2xx)")
    fun create_accepts_fullyValidPayload() {
        val body = validCreateBody(unique("strict-valid"))
        postCreate(body).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("PATCH with {clearGroup: true} returns 400")
    fun patch_rejects_clearGroupTrue() {
        // Seed a component with the required fields.
        val name = unique("strict-patch-clear")
        val seedResponse =
            postCreate(validCreateBody(name)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val patchBody = """{"version": $versionLock, "clearGroup": true}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("PATCH with {clearGroup: false, version: N} (no group key) is a no-op and preserves the existing group")
    fun patch_noOp_clearGroupFalse_preservesGroup() {
        // The Portal sends {clearGroup: false, version: N, ...} on every save where
        // the group field was not touched (see buildUpdateRequest.ts). This shape
        // must remain a 2xx that does NOT modify the existing group.
        val groupKey = "org.example.preserve.${UUID.randomUUID().toString().take(6)}"
        val name = unique("strict-patch-noop")
        val seedResponse =
            postCreate(validCreateBody(name, groupKey = groupKey)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val patchBody = """{"version": $versionLock, "clearGroup": false}"""
        val patched =
            mvc.perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.group.groupKey").value(groupKey))
                .andReturn().response.contentAsString
        // Sanity: the response still carries the original group.
        val groupAfter = objectMapper.readTree(patched)["group"]
        assert(groupAfter != null && groupAfter["groupKey"].asText() == groupKey) {
            "expected group preserved with groupKey='$groupKey'; got $groupAfter"
        }
    }

    @Test
    @DisplayName("PATCH with {group: {groupKey: \"  \"}} returns 400 (blank groupKey rejected on update path)")
    fun patch_rejects_blankGroupKey() {
        // Mirrors the create-side blank-groupKey rejection: PATCH must NOT
        // be allowed to overwrite an existing component with a whitespace
        // groupKey via `upsertGroup` (component_groups.group_key has only
        // NOT NULL + UNIQUE at the DB layer — no blank check there).
        val name = unique("strict-patch-blank-grp")
        val seedResponse =
            postCreate(validCreateBody(name)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val patchBody =
            """{"version": $versionLock, "clearGroup": false, "group": {"groupKey": "  ", "isFake": false}}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("PATCH with new {group: {...}} updates the group (2xx)")
    fun patch_accepts_newGroup() {
        val name = unique("strict-patch-newgrp")
        val seedResponse =
            postCreate(validCreateBody(name)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val newKey = "org.example.other.${UUID.randomUUID().toString().take(6)}"
        val patchBody =
            """{"version": $versionLock, "clearGroup": false, "group": {"groupKey": "$newKey", "isFake": false}}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.group.groupKey").value(newKey))
    }

    // -------------------------------------------------------------------
    // Prefix validation against configHelper.supportedGroupIds()
    //
    // Frontend already validates against `GET /rest/api/2/common/supported-groups`,
    // but a non-UI client (CLI, automation) could submit any prefix the
    // frontend never would. The server is the source of truth and must
    // reject prefixes that aren't in `components-registry.supportedGroupIds`.
    //
    // Test config (`application-common.yml`) sets the allowed list to
    // `org.octopusden.octopus,io.bcomponent,org.example` — so any other
    // prefix must produce a 400. Rejection-case fixtures use
    // `com.someoneelse.*` (RFC 2606-style placeholder, deliberately NOT
    // in the test allowed list).
    // -------------------------------------------------------------------

    @Test
    @DisplayName("CREATE returns 400 when groupKey is outside the configured supportedGroupIds")
    fun create_rejects_disallowed_groupId_prefix() {
        val body =
            """
            {
              "name": "${unique("strict-bad-prefix")}",
              "group": {"groupKey": "com.someoneelse.legacy", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", org.hamcrest.Matchers.containsString("supportedGroupIds")))
    }

    @Test
    @DisplayName("CREATE rejects 'org.exampleextra.foo' even though 'org.example' is an allowed prefix (no fuzzy prefix matching)")
    fun create_rejects_prefix_with_extra_letters() {
        // Specifically guards against `startsWith(prefix)` without a `.` separator,
        // which would let allowed=`org.example` match `org.exampleextra.foo`. The
        // validator must require an exact match OR `prefix + "."` — the test
        // input `org.exampleextra.foo` starts with the allowed `org.example`
        // characters but on a non-`.` boundary, so it must be rejected.
        val body =
            """
            {
              "name": "${unique("strict-fuzzy-prefix")}",
              "group": {"groupKey": "org.exampleextra.foo", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", org.hamcrest.Matchers.containsString("supportedGroupIds")))
    }

    @Test
    @DisplayName("CREATE accepts a groupKey whose entire value equals one of the supported prefixes")
    fun create_accepts_exact_prefix_match() {
        // `groupKey == prefix` (no trailing segments) must be accepted —
        // not just `prefix + "."` form. Mirrors the frontend's
        // `useSupportedGroups` check: `v === lp || v.startsWith(lp + '.')`.
        val body =
            """
            {
              "name": "${unique("strict-exact-prefix")}",
              "group": {"groupKey": "org.example", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        // 201 (Created) specifically — the rest of the create-success
        // cases in this file use `isCreated`; keep the assertion
        // consistent so an accidental 200 regression would surface.
        postCreate(body).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("CREATE accepts an upper-case groupKey when the allowed prefix matches case-insensitively (case is preserved on storage)")
    fun create_accepts_uppercased_groupId_when_allowed_prefix_matches_case_insensitively() {
        // Frontend (CreateComponentDialog.tsx / GeneralTab.tsx) lowercases
        // both sides before comparing the user-typed groupId against the
        // supportedGroupIds list. Without the same case-insensitive compare
        // on the CRS side, a user who types `ORG.EXAMPLE.test` passes the
        // portal pre-check (lowercased to `org.example.test` which matches
        // `org.example`) and then sees a 400 from the server — confusing UX.
        // This test pins the case-insensitive match. Additionally,
        // case must be PRESERVED on storage: the persisted groupKey is
        // exactly what the user typed (`ORG.EXAMPLE.test`), not the
        // lowercased form (we don't want silent renaming).
        val typedKey = "ORG.EXAMPLE.test.${UUID.randomUUID().toString().take(6)}"
        val body =
            """
            {
              "name": "${unique("strict-case-insensitive-create")}",
              "group": {"groupKey": "$typedKey", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        val created =
            postCreate(body).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val persistedKey = objectMapper.readTree(created)["group"]["groupKey"].asText()
        assert(persistedKey == typedKey) {
            "expected user-typed case to be preserved on storage; typed='$typedKey', got='$persistedKey'"
        }
    }

    @Test
    @DisplayName("PATCH accepts an upper-case groupKey when the allowed prefix matches case-insensitively (case is preserved on storage)")
    fun patch_accepts_uppercased_groupId_when_allowed_prefix_matches_case_insensitively() {
        val name = unique("strict-case-insensitive-patch")
        val seedResponse =
            postCreate(validCreateBody(name)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val typedKey = "ORG.EXAMPLE.other.${UUID.randomUUID().toString().take(6)}"
        val patchBody =
            """{"version": $versionLock, "clearGroup": false, "group": {"groupKey": "$typedKey", "isFake": false}}"""
        val patched =
            mvc.perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.group.groupKey").value(typedKey))
                .andReturn().response.contentAsString
        val persistedKey = objectMapper.readTree(patched)["group"]["groupKey"].asText()
        assert(persistedKey == typedKey) {
            "expected user-typed case to be preserved on storage; typed='$typedKey', got='$persistedKey'"
        }
    }

    @Test
    @DisplayName("PATCH returns 400 when the new groupKey is outside supportedGroupIds")
    fun patch_rejects_disallowed_groupId_prefix() {
        val name = unique("strict-patch-bad-prefix")
        val seedResponse =
            postCreate(validCreateBody(name)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val patchBody =
            """{"version": $versionLock, "clearGroup": false, "group": {"groupKey": "com.someoneelse.legacy", "isFake": false}}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", org.hamcrest.Matchers.containsString("supportedGroupIds")))
    }

    // -------------------------------------------------------------------
    // groupKey whitespace normalisation (Copilot review)
    //
    // `isNotBlank()` accepts `"org.example.test "` with trailing whitespace.
    // Without trimming on the way in, `component_groups.group_key` UNIQUE
    // would persist a distinct row from the canonical `"org.example.test"` —
    // the kind of duplication that breaks aggregator queries silently.
    // The fix is to trim the incoming key and use the trimmed form for
    // both validation and persistence. CREATE and PATCH share the
    // semantic.
    // -------------------------------------------------------------------

    @Test
    @DisplayName("CREATE trims whitespace around groupKey before validating and persisting")
    fun create_normalises_groupKey_whitespace() {
        val name = unique("strict-create-trim")
        val body =
            """
            {
              "name": "$name",
              "group": {"groupKey": "  org.example.trim.${UUID.randomUUID().toString().take(6)}  ", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        val created =
            postCreate(body).andExpect(status().isCreated).andReturn().response.contentAsString
        val groupKey = objectMapper.readTree(created)["group"]["groupKey"].asText()
        // Asserts trimming applied: the persisted key has no leading/trailing
        // whitespace. The leading/trailing-trim cases are both covered by the
        // single quoted-whitespace input above.
        assert(groupKey == groupKey.trim()) {
            "expected persisted groupKey to be trimmed; got '$groupKey'"
        }
        assert(groupKey.startsWith("org.example.trim.")) {
            "expected the canonical (trimmed) prefix to survive; got '$groupKey'"
        }
    }

    // -------------------------------------------------------------------
    // PATCH labels: explicit-clear vs no-op contract
    //
    // The portal's `buildUpdateRequest.ts` emits `labels: []` (literal empty
    // array, NOT absent / undefined) when the user toggles every label off
    // via the dictionary multi-select Clear action. This is an explicit
    // clear. When the labels field is untouched, the wire shape omits the
    // `labels` key entirely (Jackson + Kotlin nullability map this to
    // `request.labels == null` → "don't touch").
    //
    // These tests pin both branches against the current service-layer
    // implementation: `syncLabels(emptySet)` deletes all existing rows and
    // adds none → cleared; `request.labels == null` short-circuits the
    // sync entirely → preserved. Without these tests the contract was
    // only documented in `buildUpdateRequest.ts` comments.
    // -------------------------------------------------------------------

    @Test
    @DisplayName("PATCH with labels: [] (explicit empty array) clears all existing labels")
    fun patch_with_labels_empty_array_clears_labels() {
        val name = unique("strict-patch-labels-clear")
        val seedBody =
            """
            {
              "name": "$name",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}},
              "labels": ["lblA_${UUID.randomUUID().toString().take(6)}",
                         "lblB_${UUID.randomUUID().toString().take(6)}"]
            }
            """.trimIndent()
        val seedResponse =
            postCreate(seedBody).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()
        val seededLabels = seed["labels"].map { it.asText() }
        assert(seededLabels.size == 2) {
            "precondition: seeded component should have 2 labels; got $seededLabels"
        }

        // The portal sends this exact shape from `buildUpdateRequest.ts` when
        // the user toggles every label off — `labels: []` is the explicit-clear
        // signal, and `clearGroup: false` is always present in the wire schema.
        val patchBody =
            """{"version": $versionLock, "clearGroup": false, "labels": []}"""
        val patched =
            mvc.perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        val labelsAfter = objectMapper.readTree(patched)["labels"].map { it.asText() }
        assert(labelsAfter.isEmpty()) {
            "expected labels cleared to []; got $labelsAfter"
        }
    }

    @Test
    @DisplayName("PATCH without 'labels' key (field absent) preserves existing labels — no-op contract")
    fun patch_with_labels_absent_preserves_labels() {
        val name = unique("strict-patch-labels-noop")
        val labelA = "lblA_${UUID.randomUUID().toString().take(6)}"
        val labelB = "lblB_${UUID.randomUUID().toString().take(6)}"
        val seedBody =
            """
            {
              "name": "$name",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}},
              "labels": ["$labelA", "$labelB"]
            }
            """.trimIndent()
        val seedResponse =
            postCreate(seedBody).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        // No `labels` key at all — Kotlin/Jackson sees `request.labels == null`,
        // which means "don't touch" per the PATCH no-op contract.
        val patchBody = """{"version": $versionLock, "clearGroup": false}"""
        val patched =
            mvc.perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        val labelsAfter = objectMapper.readTree(patched)["labels"].map { it.asText() }.toSet()
        assert(labelsAfter == setOf(labelA, labelB)) {
            "expected labels preserved as [$labelA, $labelB]; got $labelsAfter"
        }
    }

    @Test
    @DisplayName("PATCH trims whitespace around groupKey before validating and persisting")
    fun patch_normalises_groupKey_whitespace() {
        val name = unique("strict-patch-trim")
        val seedResponse =
            postCreate(validCreateBody(name)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val newKey = "org.example.patchtrim.${UUID.randomUUID().toString().take(6)}"
        val patchBody =
            """{"version": $versionLock, "clearGroup": false, "group": {"groupKey": "  $newKey  ", "isFake": false}}"""
        val patched =
            mvc.perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.group.groupKey").value(newKey))
                .andReturn().response.contentAsString
        val persistedKey = objectMapper.readTree(patched)["group"]["groupKey"].asText()
        assert(persistedKey == newKey) {
            "expected PATCH to persist trimmed key '$newKey'; got '$persistedKey'"
        }
    }

    // -------------------------------------------------------------------
    // Single-value `system` field (M:N → 1:0..1 collapse).
    //
    // The previous iteration modelled system membership as a Set<String>
    // backed by a `component_systems` M:N junction. This iteration
    // collapses to a scalar `components.system_code` FK — a component
    // belongs to at most one system. Tests pin:
    //  - CREATE accepts a single value and persists it.
    //  - CREATE accepts `null`/absent (system is optional).
    //  - PATCH changes the value.
    //  - List filter `?system=A,B` is OR-semantic across the scalar
    //    (already covered by ListComponentsSystemFilterTest, but a smoke
    //    test here pins the contract from the strict-contract surface).
    // -------------------------------------------------------------------

    @Test
    @DisplayName("CREATE accepts a single `system` value and persists it on the components row")
    fun create_accepts_single_system() {
        // The test profile sets `components-registry.supportedSystems:
        // NONE,CLASSIC,ALFA` in `application-common.yml`. Service-layer
        // validation gates the `system` field against that env-config
        // allowlist (mirrors the analogous `supportedGroupIds` gate for
        // `groupKey`); the master `systems` row is auto-created by
        // `ensureSystemExists` so the FK from `components.system_code →
        // systems(code)` is always satisfied — no explicit per-test
        // master-table seeding required. `CLASSIC` is the one we use
        // for the single-value contract test here.
        val body =
            """
            {
              "name": "${unique("strict-create-single-system")}",
              "system": "CLASSIC",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.system").value("CLASSIC"))
    }

    @Test
    @DisplayName("CREATE accepts absent `system` (component without a system is valid; scalar is nullable)")
    fun create_accepts_null_system() {
        val body =
            """
            {
              "name": "${unique("strict-create-null-system")}",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.system").isEmpty)
    }

    @Test
    @DisplayName("CREATE rejects a `system` value that is not in the configured supportedSystems allowlist")
    fun create_rejects_unknown_system() {
        val body =
            """
            {
              "name": "${unique("strict-create-bad-system")}",
              "system": "NOT_A_REAL_SYSTEM_xyz",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage", org.hamcrest.Matchers.containsString("system")))
    }

    @Test
    @DisplayName("PATCH with a new `system` value updates the components.system_code column")
    fun patch_changes_system() {
        // Seed with CLASSIC, then PATCH to ALFA (both in the test
        // profile's configured `supportedSystems` allowlist).
        val name = unique("strict-patch-system")
        val seedBody =
            """
            {
              "name": "$name",
              "system": "CLASSIC",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        val seedResponse =
            postCreate(seedBody).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()
        assert(seed["system"].asText() == "CLASSIC") {
            "precondition: seeded component should carry CLASSIC; got ${seed["system"]}"
        }

        val patchBody = """{"version": $versionLock, "clearGroup": false, "system": "ALFA"}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.system").value("ALFA"))
    }

    @Test
    @DisplayName("PATCH without `system` key (field absent) preserves existing system — no-op contract")
    fun patch_without_system_key_preserves_existing() {
        val name = unique("strict-patch-system-noop")
        val seedBody =
            """
            {
              "name": "$name",
              "system": "CLASSIC",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        val seedResponse =
            postCreate(seedBody).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        // No `system` key — should be a no-op.
        val patchBody = """{"version": $versionLock, "clearGroup": false}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.system").value("CLASSIC"))
    }
}
