package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * Strict-contract validation for the v4 component create/update API.
 *
 * The only hard CREATE requirement is `baseConfiguration.build.buildSystem`:
 * a component cannot exist without a build system on its BASE configuration
 * row. The server is the source of truth and rejects a create payload missing
 * it with 400.
 *
 * **`group` is NOT a hard requirement and is NOT assigned via the API**
 * (R1 aggregator/parentComponent decouple). A ComponentGroup represents DSL
 * aggregator membership (a `components { }` owner + its sub-components) and is
 * established only by the migration/import path. On the API:
 *  - CREATE: a missing `group` is valid → `componentGroup = null`; a provided
 *    `group` is accepted but IGNORED (creating via the API does not make a
 *    component an aggregator).
 *  - PATCH: `group` is never touched — a provided `group` is ignored, and
 *    `clearGroup` (true or false) is an accepted no-op (both kept on the wire
 *    for backward compatibility).
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
@Tag("integration")
class StrictContractTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var registryConfigRepository: RegistryConfigRepository

    /** Seed the `field-config` cache row directly — the admin PUT writer is gone (code-as-config). */
    private fun seedFieldConfig(value: Map<String, Any?>) {
        val entity = registryConfigRepository.findById("field-config").orElse(RegistryConfigEntity(key = "field-config"))
        entity.value = value
        registryConfigRepository.save(entity)
    }

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
          "componentOwner": "owner1",
          "group": {"groupKey": "$groupKey", "isFake": false},
          "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
        }
        """.trimIndent()

    @Test
    @DisplayName("CREATE accepts a payload with no 'group' (group is optional; not assigned via API) → 2xx, null group")
    fun create_accepts_missingGroup() {
        // R1: group is migration-derived aggregator membership, not a required
        // create field. A component created via the API with no group is a
        // valid standalone component whose `group` is null in the response.
        val body =
            """
            {
              "name": "${unique("strict-no-group")}",
              "componentOwner": "owner1",
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.group").isEmpty)
    }

    @Test
    @DisplayName("CREATE accepts but IGNORES a provided 'group' (API does not assign aggregator membership) → 2xx, null group")
    fun create_ignoresProvidedGroup() {
        // A client may still send a `group` (backward-compat wire shape); the
        // server accepts the payload but does not persist it — group membership
        // is owned by the migration/import path, never assigned via the API.
        val body =
            """
            {
              "name": "${unique("strict-ignored-group")}",
              "componentOwner": "owner1",
              "group": {"groupKey": "org.example.ignored", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.group").isEmpty)
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
    @DisplayName("CREATE accepts payload with required fields present (2xx)")
    fun create_accepts_fullyValidPayload() {
        val body = validCreateBody(unique("strict-valid"))
        postCreate(body).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("PATCH with {clearGroup: true} is an accepted no-op (2xx) — group is migration-owned, never API-cleared")
    fun patch_clearGroupTrue_isAcceptedNoOp() {
        // R1: clearGroup is kept on the wire for backward compatibility but is a
        // no-op on the API path — the group is migration-derived aggregator
        // membership and is never mutated by an update. An API-created component
        // has a null group; clearGroup:true leaves it null and returns 2xx.
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
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.group").isEmpty)
    }

    @Test
    @DisplayName("PATCH IGNORES a provided {group: {...}} (2xx, group unchanged) — group is migration-owned, not API-editable")
    fun patch_ignoresProvidedGroup() {
        // A provided `group` on PATCH is accepted but not persisted. The seeded
        // component has a null group (API create never assigns one); after a
        // PATCH carrying a group it is still null.
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
            .andExpect(jsonPath("$.group").isEmpty)
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
              "componentOwner": "owner1",
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
              "componentOwner": "owner1",
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
        // allowlist; the master `systems` row is auto-created by
        // `ensureSystemExists` so the FK from `components.system_code →
        // systems(code)` is always satisfied — no explicit per-test
        // master-table seeding required. `CLASSIC` is the one we use
        // for the single-value contract test here.
        val body =
            """
            {
              "name": "${unique("strict-create-single-system")}",
              "componentOwner": "owner1",
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
              "componentOwner": "owner1",
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
              "componentOwner": "owner1",
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
              "componentOwner": "owner1",
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

    @Test
    @DisplayName("CREATE normalises `system` to the configured supportedSystems casing (case-insensitive validation, canonical storage)")
    fun create_normalises_system_casing_to_config() {
        // The env config (`application-common.yml`) declares
        // `supportedSystems: NONE,CLASSIC,ALFA` — all upper-case. The
        // validator is case-insensitive on the config path (a caller posting
        // `Classic` should not be rejected), but it must persist the
        // CANONICAL form so the master `systems` table doesn't accumulate
        // duplicate rows `("CLASSIC")` and `("Classic")` (PK is
        // case-sensitive). Without canonical storage, `?system=CLASSIC`
        // would miss the component stored as "Classic" and
        // `/meta/systems/dictionary` would surface both as distinct
        // entries — see PR #301 review thread.
        val body =
            """
            {
              "name": "${unique("strict-create-system-casing")}",
              "componentOwner": "owner1",
              "system": "Classic",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.system").value("CLASSIC"))
    }

    @Test
    @DisplayName("PATCH with hidden `component.system` does not surface as a system change in the audit trail")
    fun patch_with_hidden_system_does_not_ghost_audit() {
        // Repro for the PR #301 P2 review finding: when `component.system`
        // is hidden via field-config, the PATCH must NOT report a change
        // it didn't actually persist. An earlier implementation passed
        // `overrideSystem = canonicalSystem` into `scalarAuditMap` so the
        // audit newValue carried the would-have-been-written value even
        // though the FC-hidden gate stripped the entity update — a
        // misleading audit trail of a change that never happened.
        //
        // Fix: `scalarAuditMap` now reads `entity.systemCode` directly
        // after the FC-hidden gate decides whether to write. The audit
        // newValue reflects what actually persisted.
        val name = unique("strict-patch-system-hidden-audit")
        val seedBody =
            """
            {
              "name": "$name",
              "componentOwner": "owner1",
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

        // Hide `component.system` by seeding the field-config cache row directly.
        // Same fixture pattern as `FieldConfigEnforcementIntegrationTest`.
        seedFieldConfig(mapOf("component" to mapOf("system" to mapOf("visibility" to "hidden"))))

        // PATCH would write ALFA if the field were not hidden. We also change a
        // *visible* field (`displayName`) so the write is not a no-op: under
        // SYS-048 a PATCH that persists no change writes no audit row at all, so
        // without a real change there would be no UPDATE entry to inspect for the
        // ghost-write. The ghost-write guard below still holds — the resulting
        // row's `newValue.system` must read the persisted CLASSIC, never ALFA.
        val patchBody =
            """{"version": $versionLock, "clearGroup": false, "system": "ALFA", "displayName": "$name-edited"}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isOk)
            // GET reflects the unchanged stored value (already covered
            // by the FC-enforcement integration test pattern, asserted
            // here for tight repro locality).
            .andExpect(jsonPath("$.system").value("CLASSIC"))

        // Pull the audit log for this Component. The most recent UPDATE
        // entry's newValue.system MUST be `CLASSIC` (the actually-persisted
        // value), not `ALFA` (the would-have-been-written value).
        val auditBody =
            mvc
                .perform(get("/rest/api/4/audit/Component/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val auditPage = objectMapper.readTree(auditBody)
        val updateEntries =
            auditPage.path("content")
                .filter { it["action"].asText() == "UPDATE" }
        // Use JUnit assertions instead of Kotlin `assert(...)`: the Gradle
        // build does not pass `-ea` to the JVM, so `kotlin.assert` would
        // silently no-op and the ghost-write check would pass even when
        // the regression is back. JUnit `assertTrue` / `assertEquals` run
        // unconditionally and fail the test on mismatch.
        assertTrue(
            updateEntries.isNotEmpty(),
            "expected at least one UPDATE audit entry for component $id; got ${auditPage.path("content")}",
        )
        // Pick the most recent UPDATE entry (changedAt is ISO-8601, sortable lexicographically).
        val latest = updateEntries.maxBy { it["changedAt"].asText() }
        val newValueSystem = latest["newValue"]["system"]?.asText("(null)") ?: "(absent)"
        assertEquals(
            "CLASSIC",
            newValueSystem,
            "audit ghost-write detected: PATCH was stripped by FC-hidden gate but " +
                "audit newValue.system reads '$newValueSystem' (expected 'CLASSIC' — the " +
                "actually-persisted value, not the would-have-been-written 'ALFA').",
        )
    }

    @Test
    @DisplayName("PATCH normalises `system` to the configured supportedSystems casing")
    fun patch_normalises_system_casing_to_config() {
        val name = unique("strict-patch-system-casing")
        val seedBody =
            """
            {
              "name": "$name",
              "componentOwner": "owner1",
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

        // PATCH with lower-case `alfa` — should land canonical `ALFA`.
        val patchBody = """{"version": $versionLock, "clearGroup": false, "system": "alfa"}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.system").value("ALFA"))
    }
}
